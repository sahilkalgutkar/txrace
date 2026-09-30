package com.sahilkalgutkar.txrace.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Wrapper;

/**
 * The parts every txrace proxy handles the same way: identity, {@code toString}, and the JDBC
 * {@link Wrapper} methods. Everything else goes to {@link #handle}.
 */
abstract class ProxyHandler implements InvocationHandler {

    final Object real;

    ProxyHandler(Object real) {
        this.real = real;
    }

    static <T> T create(Class<T> type, ProxyHandler handler) {
        return type.cast(Proxy.newProxyInstance(ProxyHandler.class.getClassLoader(), new Class<?>[] {type}, handler));
    }

    @Override
    public final Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> describe();
            };
        }
        if (method.getDeclaringClass() == Wrapper.class) {
            // Asking for the interface the proxy implements gets the proxy, so tracing survives
            // unwrap(Connection.class). Anything more specific is the driver's own class.
            Class<?> type = (Class<?>) args[0];
            if (method.getName().equals("isWrapperFor")) {
                return type.isInstance(proxy) || ((Wrapper) real).isWrapperFor(type);
            }
            return type.isInstance(proxy) ? proxy : ((Wrapper) real).unwrap(type);
        }
        return handle(method, args);
    }

    abstract Object handle(Method method, Object[] args) throws Throwable;

    abstract String describe();

    /** Calls the method on the real object, rethrowing whatever it threw as itself. */
    final Object pass(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(real, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
