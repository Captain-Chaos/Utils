package org.pepsoft.util.mdc;

import org.slf4j.MDC;

import java.util.Map;

/**
 * A {@link RuntimeException} which captures the current contents of the {@link MDC} thread local diagnostic context at
 * the moment of throwing. The captured context is available from the {@link #getMdcContext()} method.
 *
 * <p>This exception is meant to <em>only</em> add the MDC context information to an exception chain, not any other
 * semantic information, so that it can be safely unwrapped without losing any diagnostic information (other than the
 * MDC context).
 */
public class MDCWrappingRuntimeException extends RuntimeException implements MDCContextProvider{
    public MDCWrappingRuntimeException(Throwable cause) {
        super(cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
        mdcContext = MDC.getCopyOfContextMap();
    }

    // MDCContextProvider

    public final Map<String, String> getMdcContext() {
        return mdcContext;
    }

    private final Map<String, String> mdcContext;
}