/*
 * Copyright 2020, Verizon Media.
 * Licensed under the terms of the Apache 2.0 license.
 * Please see LICENSE file in the project root for terms.
 */

package com.yahoo.oak;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Random;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;

public class ValueUtilsTest {
    private final ValueUtils valueOperator = new ValueUtils();
    private ThreadContext ctx;
    private ValueBuffer s;

    @Before
    public void init() {
        NativeMemoryAllocator allocator = new NativeMemoryAllocator(128);
        SyncRecycleMemoryManager valuesMemoryManager = new SyncRecycleMemoryManager(allocator);
        SeqExpandMemoryManager keysMemoryManager = new SeqExpandMemoryManager(allocator);
        ctx = new ThreadContext(keysMemoryManager, valuesMemoryManager);
        s = ctx.value;
        valuesMemoryManager.allocate(s.getSlice(), Integer.BYTES * 3, false);
    }

    private void putInt(int index, int value) {
        UnsafeUtils.UNSAFE.putInt(s.getAddress() + index, value);
    }

    private int getInt(int index) {
        return UnsafeUtils.UNSAFE.getInt(s.getAddress() + index);
    }

    @Test
    public void transformTest() {
        putInt(0, 10);
        putInt(4, 20);
        putInt(8, 30);

        Result result = valueOperator.transform(new Result(), s, byteBuffer -> byteBuffer.getInt(0)
                + byteBuffer.getInt(4) + byteBuffer.getInt(8));
        Assert.assertEquals(ValueUtils.ValueResult.TRUE, result.operationResult);
        Assert.assertEquals(60, ((Integer) result.value).intValue());
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void transformUpperBoundTest() {
        valueOperator.transform(new Result(), s, byteBuffer -> byteBuffer.getInt(12));
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void transformLowerBoundTest() {
        valueOperator.transform(new Result(), s, byteBuffer -> byteBuffer.getInt(-4));
    }

    @Test(timeout = 5000)
    public void cannotTransformWriteLockedTest() throws InterruptedException {
        Random random = new Random();
        final int randomValue = random.nextInt();
        CyclicBarrier barrier = new CyclicBarrier(2);
        Thread transformer = new Thread(() -> {
            try {
                barrier.await();
            } catch (InterruptedException | BrokenBarrierException e) {
                e.printStackTrace();
            }
            Result result = valueOperator.transform(new Result(), s, byteBuffer -> byteBuffer.getInt(4));
            Assert.assertEquals(ValueUtils.ValueResult.TRUE, result.operationResult);
            Assert.assertEquals(randomValue, ((Integer) result.value).intValue());
        });
        Assert.assertEquals(ValueUtils.ValueResult.TRUE, s.getSlice().lockWrite());
        transformer.start();
        try {
            barrier.await();
        } catch (InterruptedException | BrokenBarrierException e) {
            e.printStackTrace();
        }
        Thread.sleep(2000);
        putInt(4, randomValue);
        s.getSlice().unlockWrite();
        transformer.join();
    }

    @Test
    public void multipleConcurrentTransformsTest() {
        putInt(0, 10);
        putInt(4, 14);
        putInt(8, 18);
        final int parties = 4;
        CyclicBarrier barrier = new CyclicBarrier(parties);
        Thread[] threads = new Thread[parties];
        for (int i = 0; i < parties; i++) {
            threads[i] = new Thread(() -> {
                try {
                    barrier.await();
                } catch (InterruptedException | BrokenBarrierException e) {
                    e.printStackTrace();
                }
                int index = new Random().nextInt(3) * 4;
                Result result = valueOperator.transform(new Result(), s, byteBuffer -> byteBuffer.getInt(index));
                Assert.assertEquals(ValueUtils.ValueResult.TRUE, result.operationResult);
                Assert.assertEquals(10 + index, ((Integer) result.value).intValue());
            });
            threads[i].start();
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @Test
    public void cannotTransformDeletedTest() {
        s.getSlice().logicalDelete();
        Result result = valueOperator.transform(new Result(), s, byteBuffer -> byteBuffer.getInt(0));
        Assert.assertEquals(ValueUtils.ValueResult.FALSE, result.operationResult);
    }

    @Test
    public void cannotTransformedDifferentVersionTest() {
        s.getSlice().associateMMAllocation(2, -1);
        Result result = valueOperator.transform(new Result(), s, byteBuffer -> byteBuffer.getInt(0));
        Assert.assertEquals(ValueUtils.ValueResult.RETRY, result.operationResult);
    }

    @Test
    public void putWithNoResizeTest() {
        Random random = new Random();
        int[] randomValues = new int[3];
        for (int i = 0; i < randomValues.length; i++) {
            randomValues[i] = random.nextInt();
        }
        Assert.assertEquals(ValueUtils.ValueResult.TRUE, valueOperator.put(null, ctx, 10, new OakSerializer<Integer>() {
            @Override
            public void serialize(Integer object, OakScopedWriteBuffer targetBuffer) {
                for (int i = 0; i < randomValues.length; i++) {
                    targetBuffer.putInt(i * Integer.BYTES, randomValues[i]);
                }
            }

            @Override
            public Integer deserialize(OakScopedReadBuffer byteBuffer) {
                return null;
            }

            @Override
            public int calculateSize(Integer object) {
                return 0;
            }
        }, null));
        Assert.assertEquals(randomValues[0], getInt(0));
        Assert.assertEquals(randomValues[1], getInt(4));
        Assert.assertEquals(randomValues[2], getInt(8));
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void putUpperBoundTest() {
        valueOperator.put(null, ctx, 5, new OakSerializer<Integer>() {
            @Override
            public void serialize(Integer object, OakScopedWriteBuffer targetBuffer) {
                targetBuffer.putInt(12, 30);
            }

            @Override
            public Integer deserialize(OakScopedReadBuffer byteBuffer) {
                return null;
            }

            @Override
            public int calculateSize(Integer object) {
                return 0;
            }
        }, null);
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void putLowerBoundTest() {
        valueOperator.put(null, ctx, 5, new OakSerializer<Integer>() {
            @Override
            public void serialize(Integer object, OakScopedWriteBuffer targetBuffer) {
                targetBuffer.putInt(-4, 30);
            }

            @Override
            public Integer deserialize(OakScopedReadBuffer byteBuffer) {
                return null;
            }

            @Override
            public int calculateSize(Integer object) {
                return 0;
            }
        }, null);
    }

    @Test
    public void cannotPutReadLockedTest() throws InterruptedException {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Random random = new Random();
        int[] randomValues = new int[3];
        for (int i = 0; i < randomValues.length; i++) {
            randomValues[i] = random.nextInt();
        }
        Thread putter = new Thread(() -> {
            try {
                barrier.await();
            } catch (InterruptedException | BrokenBarrierException e) {
                e.printStackTrace();
            }
            valueOperator.put(null, ctx, 10, new OakSerializer<Integer>() {
                @Override
                public void serialize(Integer object, OakScopedWriteBuffer targetBuffer) {
                    for (int i = 0; i < randomValues.length; i++) {
                        targetBuffer.putInt(i * Integer.BYTES, randomValues[i]);
                    }
                }

                @Override
                public Integer deserialize(OakScopedReadBuffer byteBuffer) {
                    return null;
                }

                @Override
                public int calculateSize(Integer object) {
                    return 0;
                }
            }, null);
        });
        s.getSlice().lockRead();
        putter.start();
        try {
            barrier.await();
        } catch (InterruptedException | BrokenBarrierException e) {
            e.printStackTrace();
        }
        Thread.sleep(2000);
        int a = getInt(0);
        int b = getInt(4);
        int c = getInt(8);
        s.getSlice().unlockRead();
        putter.join();
        Assert.assertNotEquals(randomValues[0], a);
        Assert.assertNotEquals(randomValues[1], b);
        Assert.assertNotEquals(randomValues[2], c);
    }

    @Test
    public void cannotPutWriteLockedTest() throws InterruptedException {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Random random = new Random();
        int[] randomValues = new int[3];
        for (int i = 0; i < randomValues.length; i++) {
            randomValues[i] = random.nextInt();
        }
        putInt(0, randomValues[0] - 1);
        putInt(4, randomValues[1] - 1);
        putInt(8, randomValues[2] - 1);
        Thread putter = new Thread(() -> {
            try {
                barrier.await();
            } catch (InterruptedException | BrokenBarrierException e) {
                e.printStackTrace();
            }
            valueOperator.put(null, ctx, 10, new OakSerializer<Integer>() {
                @Override
                public void serialize(Integer object, OakScopedWriteBuffer targetBuffer) {
                    for (int i = 0; i < targetBuffer.capacity(); i += 4) {
                        Assert.assertEquals(randomValues[i / 4], targetBuffer.getInt(i));
                    }
                }

                @Override
                public Integer deserialize(OakScopedReadBuffer byteBuffer) {
                    return null;
                }

                @Override
                public int calculateSize(Integer object) {
                    return 0;
                }
            }, null);
        });
        s.getSlice().lockWrite();
        putter.start();
        try {
            barrier.await();
        } catch (InterruptedException | BrokenBarrierException e) {
            e.printStackTrace();
        }
        Thread.sleep(2000);
        putInt(0, randomValues[0]);
        putInt(4, randomValues[1]);
        putInt(8, randomValues[2]);
        s.getSlice().unlockWrite();
        putter.join();
    }

    @Test
    public void cannotPutInDeletedValueTest() {
        s.getSlice().logicalDelete();
        Assert.assertEquals(ValueUtils.ValueResult.FALSE, valueOperator.put(null, ctx, null, null,
            null));
    }

    @Test
    public void cannotPutToValueOfDifferentVersionTest() {
        s.getSlice().associateMMAllocation(2, -1);
        Assert.assertEquals(ValueUtils.ValueResult.RETRY, valueOperator.put(null, ctx, null, null,
            null));
    }

    @Test
    public void computeTest() {
        int value = new Random().nextInt(128);
        putInt(0, value);
        valueOperator.compute(s, oakWBuffer -> {
            oakWBuffer.putInt(0, oakWBuffer.getInt(0) * 2);
        });
        Assert.assertEquals(value * 2, getInt(0));
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void computeUpperBoundTest() {
        valueOperator.compute(s, oakWBuffer -> {
            oakWBuffer.putInt(12, 10);
        });
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void computeLowerBoundTest() {
        valueOperator.compute(s, oakWBuffer -> {
            oakWBuffer.putInt(-1, 10);
        });
    }

    @Test
    public void cannotComputeReadLockedTest() throws InterruptedException {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Random random = new Random();
        int[] randomValues = new int[3];
        for (int i = 0; i < randomValues.length; i++) {
            randomValues[i] = random.nextInt();
        }
        putInt(0, randomValues[0]);
        putInt(4, randomValues[1]);
        putInt(8, randomValues[2]);
        Thread computer = new Thread(() -> {
            try {
                barrier.await();
            } catch (InterruptedException | BrokenBarrierException e) {
                e.printStackTrace();
            }
            valueOperator.compute(s, oakWBuffer -> {
                for (int i = 0; i < 12; i += 4) {
                    oakWBuffer.putInt(i, oakWBuffer.getInt(i) + 1);
                }
            });
        });
        s.getSlice().lockRead();
        computer.start();
        try {
            barrier.await();
        } catch (InterruptedException | BrokenBarrierException e) {
            e.printStackTrace();
        }
        Thread.sleep(2000);
        int[] results = new int[3];
        for (int i = 0; i < 3; i++) {
            results[i] = getInt(i * 4);
        }
        s.getSlice().unlockRead();
        computer.join();
        Assert.assertArrayEquals(randomValues, results);
    }

    @Test
    public void cannotComputeWriteLockedTest() throws InterruptedException {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Random random = new Random();
        int[] randomValues = new int[3];
        for (int i = 0; i < randomValues.length; i++) {
            randomValues[i] = random.nextInt();
        }
        putInt(0, randomValues[0] - 1);
        putInt(4, randomValues[1] - 1);
        putInt(8, randomValues[2] - 1);
        Thread computer = new Thread(() -> {
            try {
                barrier.await();
            } catch (InterruptedException | BrokenBarrierException e) {
                e.printStackTrace();
            }
            valueOperator.compute(s, oakWBuffer -> {
                for (int i = 0; i < 12; i += 4) {
                    oakWBuffer.putInt(i, oakWBuffer.getInt(i) + 1);
                }
            });
        });
        s.getSlice().lockWrite();
        computer.start();
        try {
            barrier.await();
        } catch (InterruptedException | BrokenBarrierException e) {
            e.printStackTrace();
        }
        Thread.sleep(2000);
        for (int i = 0; i < 12; i += 4) {
            putInt(i, getInt(i) + 1);
        }
        s.getSlice().unlockWrite();
        computer.join();
        Assert.assertNotEquals(randomValues[0], getInt(0));
        Assert.assertNotEquals(randomValues[1], getInt(4));
        Assert.assertNotEquals(randomValues[2], getInt(8));
    }

    @Test
    public void cannotComputeDeletedValueTest() {
        s.getSlice().logicalDelete();
        Assert.assertEquals(ValueUtils.ValueResult.FALSE, valueOperator.compute(s, oakWBuffer -> {
        }));
    }

    @Test
    public void cannotComputeValueOfDifferentVersionTest() {
        s.getSlice().associateMMAllocation(2, -1);
        Assert.assertEquals(ValueUtils.ValueResult.RETRY, valueOperator.compute(s, oakWBuffer -> {
        }));
    }
}
