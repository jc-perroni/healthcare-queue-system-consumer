# Healthcare Queue System — Consumer

Consumer Kafka (Spring Boot) responsável por **consumir eventos de fila do SUS** publicados no tópico Kafka e **persistir estado** tanto no **PostgreSQL (schemas `und_atd1`, `und_atd2`, `und_atd3`)** quanto no **Redis** (fila + snapshots + idempotência).

## Principais features

- **Consumo de fila Kafka** via `@KafkaListener` (mensagem como `String` contendo JSON).
- **Contrato padronizado de evento** (envelope) com validação de campos obrigatórios.
- **Idempotência** por `eventId` usando Redis (evita reprocessar mensagens duplicadas).
- **Persistência no Postgres** em **schemas por unidade** (`und_atd1/2/3`):
  - `ATENDIMENTOS_UNIDADE` (estado atual do atendimento/senha)
  - `ESTADO_ATENDIMENTO` (histórico de estados por timestamp)
  - `PONTO_MEDICOS` (entrada/saída do médico no ponto)
- **Persistência no Redis**:
  - **ZSET por unidade** para representar a **fila única** (prioridade + número da senha)
  - **snapshot JSON** por atendimento (debug/consulta rápida)
- **Métrica operacional no Redis**: **tempo estimado de espera** por unidade e por tipo/prioridade (**emergência / gestante / idoso / normal**) com TTL curto.
- **Logs** e tratamento de erro:
  - Mensagem inválida (envelope) é logada e **ignorada**
  - Erros inesperados são propagados (para permitir retry)

## Validações de negócio (regras)

- **Ponto**
  - Somente colaboradores com função **MEDICO** são gravados em `PONTO_MEDICOS` (no schema da unidade).
  - Se um colaborador não-médico for enviado nos eventos de ponto, o consumer **loga** e **ignora**.
  - Se o mesmo médico tentar **entrar duas vezes** sem registrar saída, a segunda entrada é **ignorada**.

- **Retirada de senha**
  - Se um paciente tentar retirar **mais de uma senha ativa**, o consumer **mantém apenas a primeira ativa** e **cancela automaticamente** todas as posteriores.
  - A retirada “posterior” (a senha do evento) é persistida já como **CANCELADA (91)** para manter rastreabilidade no banco.
  - A numeração de senha é normalizada para **1..999** (após 999, volta para 1).

## Arquitetura (fluxo)

1. Producer publica no Kafka um JSON no formato de **envelope**.
2. Este consumer:
   - Valida/parsa o envelope
   - Aplica idempotência por `eventId`

- Atualiza Postgres (no schema resolvido por unidade)
- Atualiza Redis (fila e snapshot)

## Roteamento por unidade → schema

O schema é selecionado por evento (Hibernate multi-tenancy por **SCHEMA**) a partir de `payload.unidadeAtendimento`:

- `UPA1` → `und_atd1`
- `UPA2` → `und_atd2`
- `UPA3` → `und_atd3`

Fallbacks:

- Se `unidadeAtendimento` já vier como `und_atd2`/`UND_ATD2`, o consumer usa esse valor.
- Se não vier `unidadeAtendimento`, usa `und_atd1`.

## Contrato da mensagem (envelope)

O consumer espera a mensagem Kafka como JSON neste formato:

```json
{
  "eventId": "b7f3f3da-0e91-4b0a-9c86-7d3a12345678",
  "type": "RETIRADA_DE_SENHA",
  "occurredAt": "2026-02-13T12:34:56Z",
  "payload": {
    "unidadeAtendimento": "UPA2",
    "nrSenhaAtendimento": 123,
    "codCadastroSusPaciente": 10,
    "timestamp": "2026-02-13T12:34:56Z"
  }
}
```

- `eventId` (UUID): identificador único do evento (base da idempotência)
- `type` (string): um dos valores em **Tipos de evento suportados**
- `occurredAt` (Instant ISO-8601): quando o evento ocorreu
- `payload` (objeto JSON): dados específicos do evento
- `payload.timestamp` (opcional): quando presente, é usado como timestamp do estado/ponto; caso contrário, usa `occurredAt`

## Tipos de evento suportados

Tipos suportados (ver `EventType`):

- `MEDICO_ENTRA_NO_PONTO`
- `MEDICO_SAI_DO_PONTO`
- `RETIRADA_DE_SENHA`
- `SENHA_PRIORIZADA`
- `ATENDIMENTO_FINALIZADO`
- `SENHA_EXPIRADA`

### 1) `RETIRADA_DE_SENHA`

**Payload obrigatório:**

- `unidadeAtendimento` (string)
- `nrSenhaAtendimento` (number)
- `codCadastroSusPaciente` (number)

**Efeito:**

- Cria um registro em `ATENDIMENTOS_UNIDADE` (no schema da unidade)
- Registra histórico em `ESTADO_ATENDIMENTO` (no schema da unidade)
- Enfileira no Redis (`ZSET`) e salva snapshot

**Regras de priorização (no momento):**

- Gestante (`CADASTRO_SUS.INDICADOR_GESTANTE = 'S'`) → priorização gestante
- Idoso (`CADASTRO_SUS.IDADE_PACIENTE >= 60`) → priorização idoso
- Caso contrário → normal

> Importante: se o paciente **não existir** em `CADASTRO_SUS` (no schema da unidade), o evento é **logado e ignorado**.

### 2) `SENHA_PRIORIZADA`

**Payload obrigatório:**

- `unidadeAtendimento` (string)
- `nrSeqAtendimento` (string/number)

**Efeito:**

- Atualiza `ATENDIMENTOS_UNIDADE` para priorização **emergência**
- Registra histórico em `ESTADO_ATENDIMENTO`
- Reinsere o atendimento no ZSET com score negativo (alta prioridade)

### 3) `ATENDIMENTO_FINALIZADO` / `SENHA_EXPIRADA`

**Payload obrigatório:**

- `unidadeAtendimento` (string)
- `nrSeqAtendimento` (string/number)

**Efeito:**

- Atualiza `ATENDIMENTOS_UNIDADE.COD_ESTADO_SENHA`
- Registra histórico em `ESTADO_ATENDIMENTO`
- Remove da fila no Redis e apaga snapshot

### 4) `MEDICO_ENTRA_NO_PONTO`

**Payload obrigatório:**

- `codIdColaborador` (string/number)

**Efeito:**

- Cria um registro em `PONTO_MEDICOS` (no schema da unidade) com `HORARIO_ENTRADA`

> Importante: se o colaborador **não existir** em `COLABORADORES` (no schema da unidade), o evento é **logado e ignorado**.

### 5) `MEDICO_SAI_DO_PONTO`

**Payload obrigatório:**

- `codIdColaborador` (string/number)

**Efeito:**

- Busca o **último ponto em aberto** (sem `HORARIO_SAIDA`) e preenche `HORARIO_SAIDA`

## Persistência no Postgres (schemas `und_atd1/2/3`)

Tabelas usadas diretamente pelo consumer:

- `und_atdX.ATENDIMENTOS_UNIDADE`
  - Criação/atualização do estado e tipo de priorização do atendimento
- `und_atdX.ESTADO_ATENDIMENTO`
  - Histórico com chave composta (`NR_SEQ_ATENDIMENTO`, `COD_TIPO_ESTADO`, `TIMESTAMP_ESTADO`)
- `und_atdX.PONTO_MEDICOS`
  - Registro de entrada/saída do colaborador

Tabelas de referência **necessárias**:

- `und_atdX.TIPO_PRIORIZACAO`
  - Códigos esperados: `0..3`
- `und_atdX.TIPO_ESTADO_SENHA`
  - Códigos usados no código hoje: `1` (normal criada), `2` (gestante), `3` (idoso), `4` (emergência), `6` (finalizado), `90` (expirada)

> O código usa `getReferenceById(...)` para essas tabelas; se os códigos não existirem, vai falhar ao tentar persistir.

## Persistência no Redis

Chaves principais:

- `event:processed:<UUID>`
  - Idempotência por `eventId` (TTL padrão: 7 dias)
- `queue:zset:<unidadeAtendimento>`
  - **Fila única por unidade** (ZSET), com ordenação por prioridade + número da senha
- `atendimento:<unidadeAtendimento>:<nrSeqAtendimento>`
  - Snapshot JSON do atendimento (TTL padrão: 7 dias)
- `seq:ponto_medicos`
  - Sequência via `INCR` para `NR_SEQ_HORARIO` (coluna não identity; usada em `PONTO_MEDICOS`)
- `metrics:tempoAtendimentoMedio:<unidadeAtendimento>`
  - JSON com o **tempo estimado de espera** por tipo (normal/idoso/gestante/emergência), TTL curto (2 min)

**Ordenação da fila (ZSET):**

A fila é **única**. A prioridade define quem é atendido primeiro:

1. Emergência
2. Gestante
3. Idoso
4. Normal

Implementação de score (menor score = atende primeiro):

- `score = prioridadeRank * 1_000_000 + nrSenhaNormalizada(1..999)`
- `prioridadeRank`: emergência=0, gestante=1, idoso=2, normal=3

## Métrica no Redis: tempo médio/estimado de atendimento

O consumer calcula e salva no Redis uma visão “rápida” para sua API consultar o **tempo estimado (em minutos)** por tipo de fila.

### Onde fica (chave e TTL)

- Chave: `metrics:tempoAtendimentoMedio:<unidadeAtendimento>`
  - Ex.: `metrics:tempoAtendimentoMedio:UPA1`
- TTL: **2 minutos**

### Quando atualiza

Atualiza **após commit** da transação sempre que ocorrer:

- `MEDICO_ENTRA_NO_PONTO`
- `MEDICO_SAI_DO_PONTO`
- `ATENDIMENTO_FINALIZADO`

E também quando outros eventos alteram estado de fila/atendimento (ex.: `RETIRADA_DE_SENHA`, `SENHA_PRIORIZADA`, `SENHA_EXPIRADA`).

### Formato do JSON

Exemplo de valor salvo no Redis (string JSON):

```json
{
  "unidadeAtendimento": "UPA1",
  "calculadoEm": "2026-02-13T12:34:56Z",
  "medicosEmAtendimento": 2,
  "tempoMedioAtendimentoMin": 10,
  "emergencia": {
    "senhasAtivas": 2,
    "senhasConsideradas": 2,
    "tempoEstimadoMin": 10
  },
  "gestante": {
    "senhasAtivas": 1,
    "senhasConsideradas": 3,
    "tempoEstimadoMin": 15
  },
  "idoso": {
    "senhasAtivas": 2,
    "senhasConsideradas": 5,
    "tempoEstimadoMin": 25
  },
  "normal": {
    "senhasAtivas": 8,
    "senhasConsideradas": 13,
    "tempoEstimadoMin": 65
  }
}
```

Observações:

- `medicosEmAtendimento` = quantidade de médicos com ponto **aberto** (`HORARIO_SAIDA IS NULL`).
- `senhasAtivas` = quantidade de atendimentos daquele tipo cujo estado **não** está em: finalizado (6), expirada (90), cancelada (91).
- `senhasConsideradas` = quantidade total que fica **na frente** (ou no mesmo grupo) considerando a prioridade global.
- `tempoEstimadoMin` pode ser **null** se `medicosEmAtendimento` for 0 (sem médico no ponto).

### Fórmula usada (MVP)

Para cada tipo, o tempo estimado considera todas as prioridades **maiores** na frente:

- emergência: usa apenas `emergencia.senhasConsideradas`
- gestante: considera `emergencia + gestante`
- idoso: considera `emergencia + gestante + idoso`
- normal: considera `emergencia + gestante + idoso + normal`

$$tempoEstimadoMin = \lceil (senhasConsideradas \times tempoMedioAtendimentoMin) / medicosEmAtendimento \rceil$$

Onde `tempoMedioAtendimentoMin` hoje é fixo em **10**.

## Como consumir o tempo médio/estimado

A API só precisa ler a string JSON dessa chave no Redis.

### Via redis-cli (debug)

```bash
redis-cli GET metrics:tempoAtendimentoMedio:UPA1
```

### No serviço

- Faça `GET` na chave da unidade (ex.: `metrics:tempoAtendimentoMedio:UPA2`).
- Se não existir (TTL expirou) ou JSON inválido, trate como **“sem estimativa no momento”**.
- Use `tempoEstimadoMin` do tipo correspondente à fila que você quer mostrar:
  - emergencia → `emergencia.tempoEstimadoMin`
  - normal → `normal.tempoEstimadoMin`
  - idoso → `idoso.tempoEstimadoMin`
  - gestante → `gestante.tempoEstimadoMin`

> Dica: guarde também `calculadoEm` para exibir “estimativa calculada há X segundos”.

## Configuração

As configurações ficam em `consumer/src/main/resources/application.properties` e podem ser sobrescritas por variáveis de ambiente.

### Variáveis de ambiente suportadas

- Kafka
  - `KAFKA_BOOTSTRAP_SERVERS` (default: `localhost:29092`)
  - `KAFKA_CONSUMER_GROUP` (default: `healthcare-queue-consumer`)
  - `KAFKA_AUTO_OFFSET_RESET` (default: `earliest`)
  - `KAFKA_TOPIC_EVENTS` (default: `healthcare.queue.events.v1`)

- Postgres
  - `DB_URL` (alias) / `SPRING_DATASOURCE_URL` (default: `jdbc:postgresql://localhost:5432/healthcare_queue`)
  - `DB_USERNAME` (alias) / `SPRING_DATASOURCE_USERNAME` (default: `postgres`)
  - `DB_PASSWORD` (alias) / `SPRING_DATASOURCE_PASSWORD` (default: `postgres`)
  - `DB_DRIVER` (opcional) / `SPRING_DATASOURCE_DRIVER_CLASS_NAME` (opcional)
  - `DB_DIALECT` (opcional) / `SPRING_JPA_DATABASE_PLATFORM` (opcional)
  - `SPRING_JPA_DDL_AUTO` (default: `update`)

- Redis
  - `REDIS_HOST` (alias) / `SPRING_REDIS_HOST` (default: `localhost`)
  - `REDIS_PORT` (alias) / `SPRING_REDIS_PORT` (default: `6379`)

- Logs
  - `APP_LOG_LEVEL` (default: `INFO`)

### Exemplo de arquivo `.env`

Você pode criar um `.env` dentro de `consumer/`:

```properties
KAFKA_BOOTSTRAP_SERVERS=localhost:29092
KAFKA_CONSUMER_GROUP=healthcare-queue-consumer
KAFKA_TOPIC_EVENTS=healthcare.queue.events.v1

DB_URL=jdbc:postgresql://localhost:5432/healthcoredb
DB_USERNAME=postgres
DB_PASSWORD=senha
DB_DRIVER=org.postgresql.Driver
DB_DIALECT=org.hibernate.dialect.PostgreSQLDialect

REDIS_HOST=localhost
REDIS_PORT=6379

APP_LOG_LEVEL=INFO
```

## Docker

Este repositório inclui um Dockerfile na raiz.

Este microsserviço sobe **apenas o consumer**. Os serviços **Kafka / Postgres / Redis** sobem no stack do **producer** (ou em outro ambiente) e o consumer se conecta via variáveis de ambiente.

### Build da imagem

```bash
docker build -t healthcare-queue-consumer:latest .
```

### Rodar o container

O consumer depende de Kafka, Postgres e Redis.

Para ficar fácil para qualquer pessoa que **baixe a imagem**, a imagem já vem com defaults apontando para o **HOST** (macOS/Windows) via `host.docker.internal`, usando as portas padrão do stack:

- Kafka: `host.docker.internal:29092`
- Postgres: `jdbc:postgresql://host.docker.internal:5432/healthcoredb`
- Redis: `host.docker.internal:6379`

Assim, se o producer (ou o stack) estiver rodando e publicando essas portas no host, o comando mais simples é:

```bash
docker run --rm \
  --name healthcare-queue-consumer \
  -p 8081:8081 \
  healthcare-queue-consumer:latest
```

Linux: `host.docker.internal` pode não existir por padrão; rode com:

```bash
docker run --rm \
  --add-host=host.docker.internal:host-gateway \
  --name healthcare-queue-consumer \
  -p 8081:8081 \
  healthcare-queue-consumer:latest
```

Exemplo (ajuste os hosts/ports conforme o seu ambiente):

```bash
docker run --rm \
  --name healthcare-queue-consumer \
  -e SERVER_PORT=8081 \
  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:29092 \
  -e KAFKA_TOPIC_EVENTS=healthcare.queue.events.v1 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/healthcoredb \
  -e DB_USERNAME=postgres \
  -e DB_PASSWORD=senha \
  -e REDIS_HOST=host.docker.internal \
  -e REDIS_PORT=6379 \
  -e APP_LOG_LEVEL=INFO \
  healthcare-queue-consumer:latest
```

Observação: no Docker o consumer usa `SERVER_PORT=8081` por padrão (para não conflitar com o producer em `8080`).

### Rodar via Docker Compose (somente o consumer)

O arquivo `docker-compose.yml` na raiz sobe apenas o consumer.

```bash
docker compose up -d --build
```

Por padrão, este compose usa o modo mais simples: conecta no Kafka/Postgres/Redis via **HOST** (`host.docker.internal`) e as **portas publicadas** pelo stack do producer.

Atualização importante (Kafka): em muitos stacks Kafka, o broker anuncia (advertised) `localhost:29092`. Quando o consumer roda **em container**, ele **não consegue** acessar esse `localhost`.

Por isso, o modo padrão recomendado do compose é: consumer na **mesma rede Docker** do stack do producer, usando `kafka:9092`.

Se a sua rede for diferente, sobrescreva com `HEALTHCARE_NETWORK`.

> Dentro de um container, `localhost` aponta para o próprio container. Por isso usamos `host.docker.internal`.

Se você preferir rodar o consumer **na mesma rede Docker** do stack do producer (acessando `kafka:9092`, `postgres:5432`, `redis:6379`), use o compose alternativo:

```bash
docker network ls
HEALTHCARE_NETWORK=<NOME_DA_REDE_DO_PRODUCER> docker compose -f docker-compose.producer-network.yml up -d --build
```

Nesse modo, você pode sobrescrever endpoints com prefixo `CONSUMER_`:

- `CONSUMER_KAFKA_BOOTSTRAP_SERVERS` (default: `kafka:9092`)
- `CONSUMER_DB_URL` (default: `jdbc:postgresql://postgres:5432/healthcoredb`)
- `CONSUMER_REDIS_HOST` (default: `redis`)

### Consigo rodar “como localhost”?

Depende de **onde o consumer está rodando**:

- Se você roda o consumer **fora do Docker** (Java direto na sua máquina), então `localhost` funciona normalmente, porque `localhost` = sua máquina.
- Se você roda o consumer **dentro de um container**, então `localhost` = **o próprio container**. Nesse caso, ele **não** consegue acessar Kafka/Postgres/Redis do host via `localhost`.

Opções práticas no macOS:

1. Consumer em container → serviços no host via `host.docker.internal`

Se o stack do producer estiver rodando no Docker e publicando portas no host (ex.: Kafka `29092`, Postgres `5432`, Redis `6379`), você pode subir o consumer com o compose de “modo localhost”:

```bash
docker compose -f docker-compose.localhost.yml up -d --build
```

2. Consumer em container → serviços em outros containers (mesma rede)

Use o [docker-compose.yml](docker-compose.yml) e conecte o consumer na mesma rede do producer (serviços por nome: `kafka:9092`, `postgres:5432`, `redis:6379`).

> Dica: se você tentou `docker-compose up`, prefira `docker compose up` (Docker Compose v2).

## Como rodar

Pré-requisitos:

- Java 21+
- Kafka (broker acessível)
- Redis
- PostgreSQL com schemas `und_atd1`, `und_atd2`, `und_atd3` e tabelas de referência populadas

Rodar a aplicação (a partir da pasta `consumer/`):

```bash
./mvnw spring-boot:run
```

Rodar testes:

```bash
./mvnw test
```

> Dica: se você rodar Maven no diretório raiz do repositório, não existe `pom.xml` lá. Rode sempre dentro de `consumer/`.

## Observações importantes

- **Timezone do ponto**: o consumer converte `Instant` para `LocalTime` usando UTC. Se quiser horário local, ajuste a conversão.
- **Dados obrigatórios no banco**: eventos dependem de `CADASTRO_SUS` (paciente) e `COLABORADORES` (médico). Quando não existem, o evento é ignorado.
- **Idempotência**: a marcação do evento como processado acontece **após commit** da transação, reduzindo risco de “perder” evento se o banco falhar.
