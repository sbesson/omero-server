/*
 *   $Id$
 *
 *   Copyright 2008 Glencoe Software, Inc. All rights reserved.
 *   Use is subject to license terms supplied in LICENSE.txt
 */

package ome.services.util;

import java.sql.SQLException;

import ome.security.SecuritySystem;
import ome.system.OmeroContext;
import ome.system.Principal;
import ome.system.ServiceFactory;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.springframework.aop.framework.ProxyFactoryBean;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.orm.hibernate3.HibernateCallback;
import org.springframework.orm.hibernate3.HibernateTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Simple execution/work interface which can be used for <em>internal</em>
 * tasks which need to have a full working implementation. The
 * {@link Executor#execute(Principal, ome.services.util.Executor.Work)} method
 * ensures that {@link SecuritySystem#login(Principal)} is called before the
 * task, that a {@link TransactionCallback} and a {@link HibernateCallback}
 * surround the call, and that subsequently {@link SecuritySystem#logout()} is
 * called.
 * 
 * @author Josh Moore, josh at glencoesoftware.com
 * @since 3.0-Beta3
 */
public class Executor implements ApplicationContextAware {

    public interface Work {
        void doWork(TransactionStatus status, Session session, ServiceFactory sf);
    }

    protected OmeroContext context;

    final protected ProxyFactoryBean proxyFactory;
    final protected SecuritySystem secSystem;
    final protected String[] proxyNames;
    final protected Interceptor interceptor;

    public Executor(SecuritySystem secSystem, TransactionTemplate tt,
            HibernateTemplate ht, String[] proxyNames) {
        this.interceptor = new Interceptor(tt, ht);
        this.secSystem = secSystem;
        this.proxyNames = proxyNames;
        this.proxyFactory = new ProxyFactoryBean();
        this.proxyFactory.setInterceptorNames(this.proxyNames);
        try {
            this.proxyFactory.setProxyInterfaces(new Class[] { Work.class });
        } catch (Exception e) {
            throw new RuntimeException("Error working with Work.class; "
                    + "highly unlikely; " + "something is weird.", e);
        }
    }

    public void setApplicationContext(ApplicationContext applicationContext)
            throws BeansException {
        this.context = (OmeroContext) applicationContext;
        this.proxyFactory.setBeanFactory(context);
    }

    public void execute(Principal p, Work work) {
        ProxyFactoryBean innerFactory = new ProxyFactoryBean();
        innerFactory.copyFrom(this.proxyFactory);
        innerFactory.setTarget(work);
        innerFactory.addAdvice(this.interceptor);
        Work inner = (Work) innerFactory.getObject();

        this.proxyFactory.setTarget(inner);
        Work outer = (Work) this.proxyFactory.getObject();
        this.secSystem.login(p);
        try {
            // Arguments will be replaced after hibernate is in effect
            outer.doWork(null, null, new ServiceFactory(this.context));
        } finally {
            this.secSystem.logout();
        }
    }

    static class Interceptor implements MethodInterceptor {

        private final TransactionTemplate txTemplate;
        private final HibernateTemplate hibTemplate;

        public Interceptor(TransactionTemplate tt, HibernateTemplate ht) {
            this.txTemplate = tt;
            this.hibTemplate = ht;
        }

        public Object invoke(MethodInvocation arg0) throws Throwable {
            final Work work = (Work) arg0.getThis();
            final ServiceFactory sf = (ServiceFactory) arg0.getArguments()[2];

            txTemplate.execute(new TransactionCallback() {
                public Object doInTransaction(final TransactionStatus status) {
                    hibTemplate.execute(new HibernateCallback() {
                        public Object doInHibernate(final Session session)
                                throws HibernateException, SQLException {
                            work.doWork(status, session, sf);
                            return null;
                        }
                    }, true);
                    return null;
                }
            });
            return null;
        }

    }
}