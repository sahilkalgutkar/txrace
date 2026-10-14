package com.sahilkalgutkar.txrace.junit;

import com.sahilkalgutkar.txrace.check.Verdict;
import java.lang.reflect.Method;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * Hands a {@link RaceTest} its {@link Race}, and once the test method has returned, judges the
 * verdict by what the annotation expects. Registered by {@code @RaceTest}, so a test never names it.
 *
 * <p>The judging happens after the method rather than inside {@link Race#run}, so a test that
 * forgets to run its race fails instead of passing with nothing checked.
 */
public final class RaceExtension implements ParameterResolver, AfterTestExecutionCallback {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(RaceExtension.class);

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == Race.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        RaceTest test = annotation(context.getRequiredTestMethod());
        if (test == null) {
            throw new ParameterResolutionException("a Race is only handed to a method annotated @RaceTest, which"
                    + " judges it afterwards; " + context.getRequiredTestMethod().getName() + " is not");
        }
        ExtensionContext.Store store = context.getStore(NAMESPACE);
        if (store.get(Race.class, Race.class) != null) {
            throw new ParameterResolutionException("a race test takes one Race");
        }
        Race race = new Race(test.preemptions());
        store.put(Race.class, race);
        return race;
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        RaceTest test = annotation(context.getRequiredTestMethod());
        // The test already failed for a reason of its own, which is the one to report.
        if (test == null || context.getExecutionException().isPresent()) {
            return;
        }
        Race race = context.getStore(NAMESPACE).get(Race.class, Race.class);
        if (race == null || race.verdict() == null) {
            throw new AssertionError(context.getRequiredTestMethod().getName() + " never ran its race: take a Race"
                    + " parameter and call run(...) with the transactions");
        }
        Verdict verdict = race.verdict();
        context.publishReportEntry("txrace", verdict.report());
        switch (test.expect()) {
            case SERIAL -> {
                if (!verdict.holds()) {
                    throw new AssertionError(verdict.report());
                }
            }
            case RACE -> {
                if (verdict.counterexample().isEmpty()) {
                    throw new AssertionError("expected some order to end in a way no serial order does, but none"
                            + " did. If the race has been fixed, expect SERIAL instead.\n" + verdict.report());
                }
            }
        }
    }

    private static RaceTest annotation(Method method) {
        return AnnotationSupport.findAnnotation(method, RaceTest.class).orElse(null);
    }
}
