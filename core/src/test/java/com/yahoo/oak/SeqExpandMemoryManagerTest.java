/*
 * Copyright 2020, Verizon Media.
 * Licensed under the terms of the Apache 2.0 license.
 * Please see LICENSE file in the project root for terms.
 */

package com.yahoo.oak;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;

public class SeqExpandMemoryManagerTest {

    private SeqExpandMemoryManager seqExpandMemoryManager;

    @Before
    public void setUp() {
        BlockMemoryAllocator keysMemoryAllocator = new NativeMemoryAllocator(128);
        seqExpandMemoryManager = new SeqExpandMemoryManager(keysMemoryAllocator);
    }

    @Test
    public void allocate() {
        Slice s = new SliceSeqExpand();
        ByteBuffer bb;

        seqExpandMemoryManager.allocate(s, 4, false);
        Assert.assertEquals(4, s.getAllocatedLength());
        Assert.assertEquals(4, seqExpandMemoryManager.allocated());

        seqExpandMemoryManager.allocate(s, 4, false);
        Assert.assertEquals(4, s.getAllocatedLength());
        Assert.assertEquals(8, seqExpandMemoryManager.allocated());
    }
}
