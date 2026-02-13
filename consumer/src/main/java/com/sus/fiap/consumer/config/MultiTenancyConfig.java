package com.sus.fiap.consumer.config;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import com.sus.fiap.consumer.service.TenantContext;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MultiTenancyConfig {
	private static final String DEFAULT_TENANT = "und_atd1";

	@Bean
	public CurrentTenantIdentifierResolver currentTenantIdentifierResolver() {
		return new CurrentTenantIdentifierResolver() {
			@Override
			public String resolveCurrentTenantIdentifier() {
				String tenant = TenantContext.getCurrentTenant();
				return (tenant == null || tenant.isBlank()) ? DEFAULT_TENANT : tenant;
			}

			@Override
			public boolean validateExistingCurrentSessions() {
				return true;
			}
		};
	}

	@Bean
	public MultiTenantConnectionProvider multiTenantConnectionProvider(DataSource dataSource) {
		return new SchemaMultiTenantConnectionProvider(dataSource, DEFAULT_TENANT);
	}

	@Bean
	public HibernatePropertiesCustomizer hibernatePropertiesCustomizer(
			MultiTenantConnectionProvider multiTenantConnectionProvider,
			CurrentTenantIdentifierResolver currentTenantIdentifierResolver
	) {
		return hibernateProperties -> {
			hibernateProperties.put(AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER, multiTenantConnectionProvider);
			hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, currentTenantIdentifierResolver);
		};
	}

	static class SchemaMultiTenantConnectionProvider implements MultiTenantConnectionProvider {
		private final DataSource dataSource;
		private final String defaultTenant;

		SchemaMultiTenantConnectionProvider(DataSource dataSource, String defaultTenant) {
			this.dataSource = dataSource;
			this.defaultTenant = defaultTenant;
		}

		@Override
		public Connection getAnyConnection() throws SQLException {
			Connection connection = dataSource.getConnection();
			try {
				connection.setSchema(defaultTenant);
			} catch (Exception ignored) {
				// Best-effort
			}
			return connection;
		}

		@Override
		public void releaseAnyConnection(Connection connection) throws SQLException {
			connection.close();
		}

		@Override
		public Connection getConnection(Object tenantIdentifier) throws SQLException {
			Connection connection = getAnyConnection();
			String tenant = tenantIdentifier == null ? defaultTenant : tenantIdentifier.toString();
			if (tenant == null || tenant.isBlank()) {
				tenant = defaultTenant;
			}
			connection.setSchema(tenant);
			return connection;
		}

		@Override
		public void releaseConnection(Object tenantIdentifier, Connection connection) throws SQLException {
			try {
				connection.setSchema(defaultTenant);
			} catch (Exception ignored) {
				// Best-effort
			}
			releaseAnyConnection(connection);
		}

		@Override
		public boolean supportsAggressiveRelease() {
			return false;
		}

		@Override
		public boolean isUnwrappableAs(Class unwrapType) {
			return unwrapType.isInstance(this);
		}

		@Override
		public <T> T unwrap(Class<T> unwrapType) {
			if (unwrapType.isInstance(this)) {
				return unwrapType.cast(this);
			}
			throw new IllegalArgumentException("Não é possível desempacotar para: " + unwrapType);
		}
	}
}
