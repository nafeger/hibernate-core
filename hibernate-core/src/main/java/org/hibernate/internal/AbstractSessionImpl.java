/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2008-2011, Red Hat Inc. or third-party contributors as
 * indicated by the @author tags or express copyright attribution
 * statements applied by the authors.  All third-party contributions are
 * distributed under license by Red Hat Inc.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.hibernate.internal;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import org.hibernate.HibernateException;
import org.hibernate.MappingException;
import org.hibernate.MultiTenancyStrategy;
import org.hibernate.Query;
import org.hibernate.SQLQuery;
import org.hibernate.ScrollableResults;
import org.hibernate.SessionException;
import org.hibernate.SharedSessionContract;
import org.hibernate.cache.spi.CacheKey;
import org.hibernate.engine.jdbc.LobCreationContext;
import org.hibernate.engine.jdbc.spi.JdbcConnectionAccess;
import org.hibernate.engine.query.spi.HQLQueryPlan;
import org.hibernate.engine.query.spi.NativeSQLQueryPlan;
import org.hibernate.engine.query.spi.sql.NativeSQLQuerySpecification;
import org.hibernate.engine.spi.EntityKey;
import org.hibernate.engine.spi.NamedQueryDefinition;
import org.hibernate.engine.spi.NamedSQLQueryDefinition;
import org.hibernate.engine.spi.QueryParameters;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.engine.transaction.spi.TransactionContext;
import org.hibernate.engine.transaction.spi.TransactionEnvironment;
import org.hibernate.jdbc.WorkExecutor;
import org.hibernate.jdbc.WorkExecutorVisitable;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.service.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.service.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.hibernate.type.Type;

/**
 * Functionality common to stateless and stateful sessions
 *
 * @author Gavin King
 */
public abstract class AbstractSessionImpl implements Serializable, SharedSessionContract,
													 SessionImplementor, TransactionContext {
	private transient SessionFactoryImplementor factory;
	private final String tenantIdentifier;
	private boolean closed = false;

	protected AbstractSessionImpl(SessionFactoryImplementor factory, String tenantIdentifier) {
		this.factory = factory;
		this.tenantIdentifier = tenantIdentifier;
		if ( MultiTenancyStrategy.NONE == getFactory().getSettings().getMultiTenancyStrategy() ) {
			if ( tenantIdentifier != null ) {
				throw new HibernateException( "SessionFactory was not configured for multi-tenancy" );
			}
		}
		else {
			if ( tenantIdentifier == null ) {
				throw new HibernateException( "SessionFactory configured for multi-tenancy, but no tenant identifier specified" );
			}
		}
	}

	public SessionFactoryImplementor getFactory() {
		return factory;
	}
	
	protected void setFactory(SessionFactoryImplementor newFactory) {
		factory = newFactory;
	}

	@Override
	public TransactionEnvironment getTransactionEnvironment() {
		return getFactory().getTransactionEnvironment();
	}

	@Override
	public <T> T execute(final LobCreationContext.Callback<T> callback) {
		return getTransactionCoordinator().getJdbcCoordinator().coordinateWork(
				new WorkExecutorVisitable<T>() {
					@Override
					public T accept(WorkExecutor<T> workExecutor, Connection connection) throws SQLException {
						try {
							return callback.executeOnConnection( connection );
						}
						catch (SQLException e) {
							throw getFactory().getSQLExceptionHelper().convert(
									e,
									"Error creating contextual LOB : " + e.getMessage()
							);
						}
					}
				}
		);
	}

	@Override
	public boolean isClosed() {
		return closed;
	}

	protected void setClosed() {
		closed = true;
	}

	protected void errorIfClosed() {
		if ( closed ) {
			throw new SessionException( "Session is closed!" );
		}
	}

	@Override
	public Query getNamedQuery(String queryName) throws MappingException {
		errorIfClosed();
		NamedQueryDefinition nqd = getFactory().getNamedQuery( queryName );
		final Query query;
		if ( nqd != null ) {
			String queryString = nqd.getQueryString();
			query = new QueryImpl(
					queryString,
			        nqd.getFlushMode(),
			        this,
			        getHQLQueryPlan( queryString, false ).getParameterMetadata()
			);
			query.setComment( "named HQL query " + queryName );
		}
		else {
			NamedSQLQueryDefinition nsqlqd = getFactory().getNamedSQLQuery( queryName );
			if ( nsqlqd==null ) {
				throw new MappingException( "Named query not known: " + queryName );
			}
			query = new SQLQueryImpl(
					nsqlqd,
			        this,
			        getFactory().getQueryPlanCache().getSQLParameterMetadata( nsqlqd.getQueryString() )
			);
			query.setComment( "named native SQL query " + queryName );
			nqd = nsqlqd;
		}
		initQuery( query, nqd );
		return query;
	}

	@Override
	public Query getNamedSQLQuery(String queryName) throws MappingException {
		errorIfClosed();
		NamedSQLQueryDefinition nsqlqd = getFactory().getNamedSQLQuery( queryName );
		if ( nsqlqd==null ) {
			throw new MappingException( "Named SQL query not known: " + queryName );
		}
		Query query = new SQLQueryImpl(
				nsqlqd,
		        this,
		        getFactory().getQueryPlanCache().getSQLParameterMetadata( nsqlqd.getQueryString() )
		);
		query.setComment( "named native SQL query " + queryName );
		initQuery( query, nsqlqd );
		return query;
	}

	private void initQuery(Query query, NamedQueryDefinition nqd) {
		query.setCacheable( nqd.isCacheable() );
		query.setCacheRegion( nqd.getCacheRegion() );
		if ( nqd.getTimeout()!=null ) query.setTimeout( nqd.getTimeout().intValue() );
		if ( nqd.getFetchSize()!=null ) query.setFetchSize( nqd.getFetchSize().intValue() );
		if ( nqd.getCacheMode() != null ) query.setCacheMode( nqd.getCacheMode() );
		query.setReadOnly( nqd.isReadOnly() );
		if ( nqd.getComment() != null ) query.setComment( nqd.getComment() );
	}

	@Override
	public Query createQuery(String queryString) {
		errorIfClosed();
		QueryImpl query = new QueryImpl(
				queryString,
		        this,
		        getHQLQueryPlan( queryString, false ).getParameterMetadata()
		);
		query.setComment( queryString );
		return query;
	}

	@Override
	public SQLQuery createSQLQuery(String sql) {
		errorIfClosed();
		SQLQueryImpl query = new SQLQueryImpl(
				sql,
		        this,
		        getFactory().getQueryPlanCache().getSQLParameterMetadata( sql )
		);
		query.setComment( "dynamic native SQL query" );
		return query;
	}

	protected HQLQueryPlan getHQLQueryPlan(String query, boolean shallow) throws HibernateException {
		return getFactory().getQueryPlanCache().getHQLQueryPlan( query, shallow, getEnabledFilters() );
	}

	protected NativeSQLQueryPlan getNativeSQLQueryPlan(NativeSQLQuerySpecification spec) throws HibernateException {
		return getFactory().getQueryPlanCache().getNativeSQLQueryPlan( spec );
	}

	@Override
	public List list(NativeSQLQuerySpecification spec, QueryParameters queryParameters)
			throws HibernateException {
		return listCustomQuery( getNativeSQLQueryPlan( spec ).getCustomQuery(), queryParameters );
	}

	@Override
	public ScrollableResults scroll(NativeSQLQuerySpecification spec, QueryParameters queryParameters)
			throws HibernateException {
		return scrollCustomQuery( getNativeSQLQueryPlan( spec ).getCustomQuery(), queryParameters );
	}

	@Override
	public String getTenantIdentifier() {
		return tenantIdentifier;
	}

	@Override
	public EntityKey generateEntityKey(Serializable id, EntityPersister persister) {
		return new EntityKey( id, persister, getTenantIdentifier() );
	}

	@Override
	public CacheKey generateCacheKey(Serializable id, Type type, String entityOrRoleName) {
		return new CacheKey( id, type, entityOrRoleName, getTenantIdentifier(), getFactory() );
	}

	private transient JdbcConnectionAccess jdbcConnectionAccess;

	@Override
	public JdbcConnectionAccess getJdbcConnectionAccess() {
		if ( jdbcConnectionAccess == null ) {
			if ( MultiTenancyStrategy.NONE == getFactory().getSettings().getMultiTenancyStrategy() ) {
				jdbcConnectionAccess = new NonContextualJdbcConnectionAccess(
						getFactory().getServiceRegistry().getService( ConnectionProvider.class )
				);
			}
			else {
				jdbcConnectionAccess = new ContextualJdbcConnectionAccess(
						getFactory().getServiceRegistry().getService( MultiTenantConnectionProvider.class )
				);
			}
		}
		return jdbcConnectionAccess;
	}

	private static class NonContextualJdbcConnectionAccess implements JdbcConnectionAccess, Serializable {
		private final ConnectionProvider connectionProvider;

		private NonContextualJdbcConnectionAccess(ConnectionProvider connectionProvider) {
			this.connectionProvider = connectionProvider;
		}

		@Override
		public Connection obtainConnection() throws SQLException {
			return connectionProvider.getConnection();
		}

		@Override
		public void releaseConnection(Connection connection) throws SQLException {
			connectionProvider.closeConnection( connection );
		}
	}

	private class ContextualJdbcConnectionAccess implements JdbcConnectionAccess, Serializable {
		private final MultiTenantConnectionProvider connectionProvider;

		private ContextualJdbcConnectionAccess(MultiTenantConnectionProvider connectionProvider) {
			this.connectionProvider = connectionProvider;
		}

		@Override
		public Connection obtainConnection() throws SQLException {
			if ( tenantIdentifier == null ) {
				throw new HibernateException( "Tenant identifier required!" );
			}
			return connectionProvider.getConnection( tenantIdentifier );
		}

		@Override
		public void releaseConnection(Connection connection) throws SQLException {
			if ( tenantIdentifier == null ) {
				throw new HibernateException( "Tenant identifier required!" );
			}
			connectionProvider.releaseConnection( tenantIdentifier, connection );
		}
	}
}
