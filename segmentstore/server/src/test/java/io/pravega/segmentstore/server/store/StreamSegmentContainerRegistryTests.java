/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.segmentstore.server.store;

import com.google.common.util.concurrent.AbstractService;
import com.google.common.util.concurrent.Service;
import io.pravega.common.concurrent.FutureHelpers;
import io.pravega.common.concurrent.ServiceHelpers;
import io.pravega.common.util.ReusableLatch;
import io.pravega.segmentstore.contracts.AttributeUpdate;
import io.pravega.segmentstore.contracts.ContainerNotFoundException;
import io.pravega.segmentstore.contracts.ReadResult;
import io.pravega.segmentstore.contracts.SegmentProperties;
import io.pravega.segmentstore.server.ContainerHandle;
import io.pravega.segmentstore.server.SegmentContainer;
import io.pravega.segmentstore.server.SegmentContainerFactory;
import io.pravega.segmentstore.server.ServiceListeners;
import io.pravega.test.common.AssertExtensions;
import io.pravega.test.common.IntentionalException;
import io.pravega.test.common.ThreadPooledTestSuite;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Cleanup;
import lombok.val;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/**
 * Unit tests for the StreamSegmentContainerRegistry class.
 */
public class StreamSegmentContainerRegistryTests extends ThreadPooledTestSuite {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    @Rule
    public Timeout globalTimeout = Timeout.seconds(TIMEOUT.getSeconds());

    @Override
    protected int getThreadPoolSize() {
        return 3;
    }

    /**
     * Tests the getContainer method for registered and unregistered containers.
     */
    @Test
    public void testGetContainer() throws Exception {
        final int containerCount = 1000;
        TestContainerFactory factory = new TestContainerFactory();
        @Cleanup
        StreamSegmentContainerRegistry registry = new StreamSegmentContainerRegistry(factory, executorService());

        HashSet<Integer> expectedContainerIds = new HashSet<>();
        List<CompletableFuture<ContainerHandle>> handleFutures = new ArrayList<>();
        for (int containerId = 0; containerId < containerCount; containerId++) {
            handleFutures.add(registry.startContainer(containerId, TIMEOUT));
            expectedContainerIds.add(containerId);
        }

        List<ContainerHandle> handles = FutureHelpers.allOfWithResults(handleFutures).join();
        HashSet<Integer> actualHandleIds = new HashSet<>();
        for (ContainerHandle handle : handles) {
            actualHandleIds.add(handle.getContainerId());
            SegmentContainer container = registry.getContainer(handle.getContainerId());
            Assert.assertTrue("Wrong container Java type.", container instanceof TestContainer);
            Assert.assertEquals("Unexpected container Id.", handle.getContainerId(), container.getId());
            container.close();
        }

        AssertExtensions.assertContainsSameElements("Unexpected container ids registered.", expectedContainerIds, actualHandleIds);

        AssertExtensions.assertThrows(
                "getContainer did not throw when passed an invalid container id.",
                () -> registry.getContainer(containerCount + 1),
                ex -> ex instanceof ContainerNotFoundException);
    }

    /**
     * Tests the ability to stop the container via the stopContainer() method.
     */
    @Test
    public void testStopContainer() throws Exception {
        final int containerId = 123;
        TestContainerFactory factory = new TestContainerFactory();
        @Cleanup
        StreamSegmentContainerRegistry registry = new StreamSegmentContainerRegistry(factory, executorService());
        ContainerHandle handle = registry.startContainer(containerId, TIMEOUT).join();

        // Register a Listener for the Container.Stop event. Make this a Future since these callbacks are invoked async
        // so they may finish executing after stop() finished.
        CompletableFuture<Integer> stopListenerCallback = new CompletableFuture<>();
        handle.setContainerStoppedListener(stopListenerCallback::complete);

        TestContainer container = (TestContainer) registry.getContainer(handle.getContainerId());
        Assert.assertFalse("Container is closed before being shut down.", container.isClosed());

        registry.stopContainer(handle, TIMEOUT).join();
        Assert.assertEquals("Unexpected value passed to Handle.stopListenerCallback or callback was not invoked.",
                containerId, (int) stopListenerCallback.join());
        Assert.assertTrue("Container is not closed after being shut down.", container.isClosed());
        AssertExtensions.assertThrows(
                "Container is still registered after being shut down.",
                () -> registry.getContainer(handle.getContainerId()),
                ex -> ex instanceof ContainerNotFoundException);
    }

    /**
     * Tests the ability to detect a container failure and unregister the container in case the container fails on startup.
     */
    @Test
    public void testContainerFailureOnStartup() throws Exception {
        final int containerId = 123;
        TestContainerFactory factory = new TestContainerFactory(new IntentionalException());
        @Cleanup
        StreamSegmentContainerRegistry registry = new StreamSegmentContainerRegistry(factory, executorService());

        AssertExtensions.assertThrows(
                "Unexpected exception thrown upon failed container startup.",
                registry.startContainer(containerId, TIMEOUT)::join,
                ex -> ex instanceof IntentionalException || (ex instanceof IllegalStateException && ex.getCause() instanceof IntentionalException));

        AssertExtensions.assertThrows(
                "Container is registered even if it failed to start.",
                () -> registry.getContainer(containerId),
                ex -> ex instanceof ContainerNotFoundException);
    }

    /**
     * Tests the ability to detect a container failure and unregister the container in case the container fails while running.
     */
    @Test
    public void testContainerFailureWhileRunning() throws Exception {
        final int containerId = 123;
        TestContainerFactory factory = new TestContainerFactory();
        @Cleanup
        StreamSegmentContainerRegistry registry = new StreamSegmentContainerRegistry(factory, executorService());

        ContainerHandle handle = registry.startContainer(containerId, TIMEOUT).join();

        // Register a Listener for the Container.Stop event. Make this a Future since these callbacks are invoked async
        // so they may finish executing after stop() finished.
        CompletableFuture<Integer> stopListenerCallback = new CompletableFuture<>();
        handle.setContainerStoppedListener(stopListenerCallback::complete);

        TestContainer container = (TestContainer) registry.getContainer(handle.getContainerId());

        // Fail the container and wait for it to properly terminate.
        container.fail(new IntentionalException());
        ServiceListeners.awaitShutdown(container, false);
        Assert.assertEquals("Unexpected value passed to Handle.stopListenerCallback or callback was not invoked.",
                containerId, (int) stopListenerCallback.join());
        AssertExtensions.assertThrows(
                "Container is still registered after failure.",
                () -> registry.getContainer(containerId),
                ex -> ex instanceof ContainerNotFoundException);
    }

    /**
     * Tests a scenario where a container startup is requested immediately after the shutdown of the same container or
     * while that one is running. This tests both the case when a container auto-shuts down due to failure and when it
     * is shut down in a controlled manner.
     */
    @Test
    public void testStartAlreadyRunning() throws Exception {
        final int containerId = 1;
        TestContainerFactory factory = new TestContainerFactory();
        @Cleanup
        StreamSegmentContainerRegistry registry = new StreamSegmentContainerRegistry(factory, executorService());

        registry.startContainer(containerId, TIMEOUT).join();
        TestContainer container1 = (TestContainer) registry.getContainer(containerId);

        // 1. While running.
        AssertExtensions.assertThrows("startContainer() did not throw for already registered container.",
                () -> registry.startContainer(containerId, TIMEOUT),
                ex -> ex instanceof IllegalArgumentException);

        // 2. After a container fails - while shutting down.
        container1.stopSignal = new ReusableLatch(); // Manually control when the Container actually shuts down.
        container1.fail(new IntentionalException());
        val startContainer2 = registry.startContainer(containerId, TIMEOUT);
        Assert.assertFalse("startContainer() completed before previous container shut down (with failure).", startContainer2.isDone());

        container1.stopSignal.release();
        startContainer2.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        TestContainer container2 = (TestContainer) registry.getContainer(containerId);

        Assert.assertEquals("Container1 was not shut down (with failure).", Service.State.FAILED, container1.state());
        Assert.assertEquals("Container2 was not started properly.", Service.State.RUNNING, container2.state());

        // 3. After a controlled shutdown - while shutting down.
        container2.stopSignal = new ReusableLatch(); // Manually control when the Container actually shuts down.
        container2.stopAsync();
        val startContainer3 = registry.startContainer(containerId, TIMEOUT);
        Assert.assertFalse("startContainer() completed before previous container shut down (normally).", startContainer3.isDone());

        container2.stopSignal.release();
        startContainer3.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        TestContainer container3 = (TestContainer) registry.getContainer(containerId);

        Assert.assertEquals("Container2 was not shut down (normally).", Service.State.TERMINATED, container2.state());
        Assert.assertEquals("Container3 was not started properly.", Service.State.RUNNING, container3.state());
    }

    //region TestContainerFactory

    private class TestContainerFactory implements SegmentContainerFactory {
        private final Exception startException;

        TestContainerFactory() {
            this(null);
        }

        TestContainerFactory(Exception startException) {
            this.startException = startException;
        }

        @Override
        public SegmentContainer createStreamSegmentContainer(int containerId) {
            return new TestContainer(containerId, this.startException);
        }
    }

    //endregion

    //region TestContainer

    private class TestContainer extends AbstractService implements SegmentContainer {
        private final int id;
        private final Exception startException;
        private Exception stopException;
        private final AtomicBoolean closed;
        private ReusableLatch stopSignal;

        TestContainer(int id, Exception startException) {
            this.id = id;
            this.startException = startException;
            this.closed = new AtomicBoolean();
        }

        public void fail(Exception ex) {
            this.stopException = ex;
            stopAsync();
        }

        public boolean isClosed() {
            return this.closed.get();
        }

        @Override
        public int getId() {
            return this.id;
        }

        @Override
        public void close() {
            if (!this.closed.getAndSet(true)) {
                FutureHelpers.await(ServiceHelpers.stopAsync(this, executorService()));
            }
        }

        @Override
        protected void doStart() {
            executorService().execute(() -> {
                if (this.startException != null) {
                    notifyFailed(this.startException);
                } else {
                    notifyStarted();
                }
            });
        }

        @Override
        protected void doStop() {
            executorService().execute(() -> {
                ReusableLatch signal = this.stopSignal;
                if (signal != null) {
                    // Wait until we are told to stop.
                    signal.awaitUninterruptibly();
                }

                if (state() != State.FAILED && state() != State.TERMINATED && this.stopException != null) {
                    notifyFailed(this.stopException);
                } else {
                    notifyStopped();
                }
            });
        }

        //region Unimplemented methods

        @Override
        public CompletableFuture<Void> append(String streamSegmentName, byte[] data, Collection<AttributeUpdate> attributeUpdates, Duration timeout) {
            return null;
        }

        @Override
        public CompletableFuture<Void> append(String streamSegmentName, long offset, byte[] data, Collection<AttributeUpdate> attributeUpdates, Duration timeout) {
            return null;
        }

        @Override
        public CompletableFuture<Void> updateAttributes(String streamSegmentName, Collection<AttributeUpdate> attributeUpdates, Duration timeout) {
            return null;
        }

        @Override
        public CompletableFuture<ReadResult> read(String streamSegmentName, long offset, int maxLength, Duration timeout) {
            return null;
        }

        @Override
        public CompletableFuture<SegmentProperties> getStreamSegmentInfo(String streamSegmentName, boolean waitForPendingOps, Duration timeout) {
            return null;
        }

        @Override
        public CompletableFuture<Void> createStreamSegment(String streamSegmentName, Collection<AttributeUpdate> attributes, Duration timeout) {
            return null;
        }

        @Override
        public CompletableFuture<String> createTransaction(String parentStreamSegmentName, UUID transactionId, Collection<AttributeUpdate> attributes, Duration timeout) {
            return null;
        }

        @Override
        public CompletableFuture<Void> mergeTransaction(String transactionName, Duration timeout) {
            return null;
        }

        @Override
        public CompletableFuture<Long> sealStreamSegment(String streamSegmentName, Duration timeout) {
            return null;
        }

        @Override
        public CompletableFuture<Void> deleteStreamSegment(String streamSegmentName, Duration timeout) {
            return null;
        }

        @Override
        public Collection<SegmentProperties> getActiveSegments() {
            return null;
        }

        //endregion
    }

    //endregion
}