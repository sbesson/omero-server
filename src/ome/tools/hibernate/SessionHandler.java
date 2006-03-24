/*
 * ome.tools.hibernate.SessionHandler
 *
 *------------------------------------------------------------------------------
 *
 *  Copyright (C) 2005 Open Microscopy Environment
 *      Massachusetts Institute of Technology,
 *      National Institutes of Health,
 *      University of Dundee
 *
 *
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation; either
 *    version 2.1 of the License, or (at your option) any later version.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public
 *    License along with this library; if not, write to the Free Software
 *    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 *------------------------------------------------------------------------------
 */
package ome.tools.hibernate;

// Java imports
import java.sql.Connection;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import javax.sql.DataSource;

// Third-party libraries
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.orm.hibernate3.HibernateInterceptor;
import org.springframework.orm.hibernate3.SessionFactoryUtils;
import org.springframework.orm.hibernate3.SessionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// Application-internal dependencies
import ome.api.StatefulServiceInterface;
import ome.conditions.InternalException;

/**
 * holder for Hibernate sessions in stateful servics. A count of calls is kept.
 * 
 * @author Josh Moore &nbsp;&nbsp;&nbsp;&nbsp; <a
 *         href="mailto:josh.moore@gmx.de">josh.moore@gmx.de</a>
 * @version 3.0 <small> (<b>Internal version:</b> $Rev$ $Date$) </small>
 * @since 3.0
 */
class SessionStatus
{

    int     calls = 0;

    Session session;

    SessionStatus(Session session)
    {
        if (null == session)
            throw new IllegalArgumentException("No null sessions.");

        this.session = session;
    }

}

/**
 * interceptor which delegates to
 * {@link org.springframework.orm.hibernate3.HibernateInterceptor} for stateless
 * services but which keeps a {@link java.util.WeakHashMap} of sessions keyed by
 * the stateful service reference.
 *
 * original idea from: 
 * http://opensource2.atlassian.com/confluence/spring/pages/viewpage.action?pageId=1447
 *
 * See also:
 * http://sourceforge.net/forum/message.php?msg_id=2455707
 * http://forum.springframework.org/archive/index.php/t-10344.html
 * http://opensource2.atlassian.com/projects/spring/browse/SPR-746
 * 
 * and these:
 * http://www.hibernate.org/43.html#A5
 * http://www.carbonfive.com/community/archives/2005/07/ive_been_meanin.html
 * http://www.hibernate.org/377.html
 * 
 * @author Josh Moore &nbsp;&nbsp;&nbsp;&nbsp; <a
 *         href="mailto:josh.moore@gmx.de">josh.moore@gmx.de</a>
 * @version 3.0 <small> (<b>Internal version:</b> $Rev$ $Date$) </small>
 * @since 3.0
 */
public class SessionHandler implements MethodInterceptor
{

    private final static Log           log      = LogFactory
                                                        .getLog(SessionHandler.class);

    private Map<Object, SessionStatus> sessions = Collections
                                                        .synchronizedMap(new WeakHashMap<Object, SessionStatus>());

    private HibernateInterceptor       delegate;

    private DataSource                 dataSource;

    private SessionFactory             factory;

    public SessionHandler(DataSource dataSource, SessionFactory factory)
    {
        this.dataSource = dataSource;
        this.delegate = new HibernateInterceptor();
        this.factory = factory;

        this.delegate.setSessionFactory(factory);

    }

    /**
     * delegates to {@link HibernateInterceptor} or manages sessions internally,
     * based on the type of service.
     */
    public Object invoke(MethodInvocation invocation) throws Throwable
    {
        // Stateless; normal semantics.
        if (!StatefulServiceInterface.class.isAssignableFrom(
                invocation.getThis().getClass()))
        {
            if ( log.isDebugEnabled())
                log.debug("Delegating session creation to HibernateInterceptor.");
            
            return delegate.invoke(invocation);
        }

        // Stateful; let's get to work.
        return doStateful(invocation);
    }

    private Object doStateful(MethodInvocation invocation) throws Throwable
    {
        Object result = null;

        try
        {
            newOrRestoredSession(invocation);
            result = invocation.proceed();
            return result;
        }

        finally
        {
            if (isCloseSession(invocation)) closeSession();
            else
                disconnectSession();
            resetThreadSession();

            // Everything successfully turned off. Decrement.
            if (sessions.containsKey(invocation.getThis()))
                sessions.get(invocation.getThis()).calls--;

        }
    }

    private void newOrRestoredSession(MethodInvocation invocation)
            throws HibernateException
    {
        
        if (isSessionBoundToThread())
        {

            String msg = "Dirty Hibernate Session "
                    + sessionBoundToThread() + " found in Thread "
                    + Thread.currentThread();

            doCloseSession();
            resetThreadSession();
            throw new InternalException(msg);
        }
        
        SessionStatus status = sessions.get(invocation.getThis());

        if (status == null || !status.session.isOpen())
        {
            
            if ( log.isDebugEnabled())
                log.debug("Replacing null or closed session.");
            
            status = new SessionStatus(acquireAndBindSession());
            sessions.put(invocation.getThis(), status);
        } else
        {
            if (status.calls > 1)
                throw new InternalException(
                        "Hibernate session is not re-entrant.\n" +
                        "Either you have two threads operating on the same " +
                        "stateful object (don't do this)\n or you have a " +
                        "recursive call (recurse on the unwrapped object). ");

            bindSession(status.session);
            reconnectSession(status.session);
        }

        // It's ready to be used. Increment.
        status.calls++;

    }

    private void closeSession() throws Exception
    {

        if (isSessionBoundToThread())
        {
            Session session = sessionBoundToThread();
            try
            {
                session.connection().commit();
                doCloseSession();
            } catch (Exception e)
            {
                throw e;
            } finally
            {
                resetThreadSession();
            }

        }

    }

    // ~ SESSIONS
    // =========================================================================

    private boolean isCloseSession(MethodInvocation invocation)
    {
        return "destroy".equals(invocation.getMethod().getName());
    }

    private Session acquireAndBindSession() throws HibernateException
    {
        Session session = factory.openSession();
        bindSession(session);
        return session;
    }

    private void bindSession(Session session) 
    {
        SessionHolder sessionHolder = new SessionHolder(session);
        sessionHolder.setTransaction(sessionHolder.getSession()
                .beginTransaction());
        TransactionSynchronizationManager.bindResource(factory, sessionHolder);
        TransactionSynchronizationManager.initSynchronization();
    }
    
    private void doCloseSession() throws HibernateException
    {
        if (isSessionBoundToThread())
        {
            sessionBoundToThread().close();
        }
    }

    private Session sessionBoundToThread()
    {
        return SessionFactoryUtils.getSession(factory, false);
    }

    private boolean isSessionBoundToThread()
    {
        return TransactionSynchronizationManager.hasResource(factory)
                && sessionBoundToThread() != null;
    }

    private void resetThreadSession()
    {
        if (isSessionBoundToThread())
        {
            TransactionSynchronizationManager.unbindResource(factory);
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void reconnectSession(Session session) throws HibernateException
    {
        if (session.isConnected())
        {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            session.reconnect(connection);
        }
    }

    private void disconnectSession() throws HibernateException
    {
        if (isSessionBoundToThread()
                && SessionFactoryUtils.getSession(factory, false).isConnected())
        {
            sessionBoundToThread().disconnect();
        }
    }

}
