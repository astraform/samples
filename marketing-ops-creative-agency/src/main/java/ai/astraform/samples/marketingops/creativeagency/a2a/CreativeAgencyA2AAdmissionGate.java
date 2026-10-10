package ai.astraform.samples.marketingops.creativeagency.a2a;

import ai.astraform.samples.marketingops.creativeagency.config.CreativeAgencyA2aProperties;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** Prevents requests from reaching the SDK after all bounded worker slots are committed. */
@Component
public final class CreativeAgencyA2AAdmissionGate {

    static final String CONTEXT_KEY = CreativeAgencyA2AAdmissionGate.class.getName() + ".lease";

    private final int capacity;
    private final Semaphore permits;

    public CreativeAgencyA2AAdmissionGate(CreativeAgencyA2aProperties properties) {
        this.capacity = properties.getMaxConcurrentRequests();
        this.permits = new Semaphore(capacity);
    }

    Lease tryAcquire() {
        return permits.tryAcquire() ? new Lease(permits) : null;
    }

    int capacity() {
        return capacity;
    }

    int availablePermits() {
        return permits.availablePermits();
    }

    Lease executionLease(RequestContext context) {
        if (context.getCallContext() == null) {
            return null;
        }
        Object value = context.getCallContext().getState().get(CONTEXT_KEY);
        return value instanceof Lease lease ? lease : null;
    }

    static final class Lease {

        private static final int ADMITTED = 0;
        private static final int RUNNING = 1;
        private static final int RELEASED = 2;

        private final Semaphore permits;
        private final AtomicInteger state = new AtomicInteger(ADMITTED);

        private Lease(Semaphore permits) {
            this.permits = permits;
        }

        boolean beginExecution() {
            return state.compareAndSet(ADMITTED, RUNNING);
        }

        void cancelBeforeExecution() {
            if (state.compareAndSet(ADMITTED, RELEASED)) {
                permits.release();
            }
        }

        void completeExecution() {
            if (state.compareAndSet(RUNNING, RELEASED)) {
                permits.release();
            }
        }
    }
}
