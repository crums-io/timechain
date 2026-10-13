/*
 * Copyright 2024 Babak Farhang
 */
package io.crums.tc.notary.d;


import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.crums.sldg.json.HashEncoding;
import io.crums.tc.Constants;
import io.crums.tc.Crum;
import io.crums.tc.Crumtrail;
import io.crums.tc.Receipt;
import io.crums.tc.TimeBinner;
import io.crums.tc.json.CrumtrailParser;
import io.crums.tc.notary.CargoBlock;
import io.crums.tc.notary.CargoChain;
import io.crums.tc.notary.Notary;
import io.crums.testing.IoTestCase;
import io.crums.util.collections.Lists;
import io.crums.util.json.JsonPrinter;

import org.junit.jupiter.api.Test;

/**
 * Also tests the "lock-free", file-based concurrency model {@linkplain CargoChain}
 * and {@linkplain CargoBlock}s implement.
 * <p>
 * <em>Some tests may fail due to timing issues, particularly if the machine is
 * under other load.</em>
 * </p>
 */
public class NotaryDTest extends IoTestCase {
  
  
  @Test
  public void testIdle() throws Exception {

    
    final Object label = new Object() { };
    final TimeBinner binner = TimeBinner.MILLIS_64;
    final long startUtc = System.currentTimeMillis();
    final int blocksRetained = 100;

    final File dir = newMethodRunDir(label);

    
    NotaryD notaryD;
    {
      var notary = Notary.incept(
          dir,
          binner,
          startUtc,
          blocksRetained);
      
      notaryD = new NotaryD(notary);
    }

    assertTrue(notaryD.isOpen());
    Thread.sleep(100);
    assertTrue(notaryD.isOpen());
    notaryD.close();
    assertFalse(notaryD.isOpen());
    
  }
  

  @Test
  public void test1000() throws Exception {

    
    final Object label = new Object() { };
    final TimeBinner binner = TimeBinner.EIGTH_SEC;
    final long startUtc = System.currentTimeMillis();
    final int blocksRetained = 100;
    final Random random = new Random(210L);
    final int crumCount = 1000;
    final int sampleIndex = crumCount / 2;

    final File dir = newMethodRunDir(label);
    
    final String name = method(label);
    
    var out = System.out;
    out.println();
    out.println(name + ":");

    
    List<ByteBuffer> wHashes = new ArrayList<>();
    for (int count = crumCount; count-- > 0; ) {
      byte[] rhash = new byte[Constants.HASH_WIDTH];
      random.nextBytes(rhash);
      wHashes.add(ByteBuffer.wrap(rhash));
    }
    

    NotaryD notaryD;
    {
      var notary = Notary.incept(
          dir,
          binner,
          startUtc,
          blocksRetained);
      
      notaryD = new NotaryD(notary);
    }

    out.println(
        "chain params:   " + notaryD.settings().chainParams());

    // collect the receipts quickly, check'em after..
    
    List<Receipt> receipts = new ArrayList<>();
    for (int index = 0; index < crumCount; ++index) {
      var rcpt = notaryD.witness(wHashes.get(index).slice());
      receipts.add(rcpt);
    }
    

    // check the receipts..
    long lastUtc = 0;
    for (int index = 0; index < crumCount; ++index) {
      var rcpt = receipts.get(index);
      assertFalse(rcpt.hasTrail());
      assertEquals(wHashes.get(index), rcpt.crum().hash());
      assertTrue(rcpt.crum().utc() >= lastUtc);
      lastUtc = rcpt.crum().utc();
    }
    long lastBlockUtcEnd =
        binner.binTime(lastUtc) + binner.duration();
    
    final long commitSlack = 100;
    
    final long targetCommitUtc = lastBlockUtcEnd + commitSlack;
    
    long now = System.currentTimeMillis();
    
    out.println("commit no.: " + notaryD.blockCount());
    
    if (now < targetCommitUtc)
      Thread.sleep(targetCommitUtc - now);
    
    assertTrue(notaryD.blockCount() > 0);
    
    Receipt sample = null;
    for (int index = 0; index < crumCount; ++index) {
      var rcpt = receipts.get(index);
      var uRcpt = notaryD.update(rcpt.crum());
      assertTrue(uRcpt.hasTrail());
      assertEquals(rcpt.crum(), uRcpt.crum());
      if (index == sampleIndex)
        sample = uRcpt;
    }
    
    notaryD.close();
    out.println("sample trail..");
    print(sample.trail());
  }
  
  
  static class Witness implements Callable<Witness> {
    
    final List<ByteBuffer> hashes;
    final List<Receipt> receipts;
    final Notary notary;
    
    Witness(List<ByteBuffer> hashes, Notary notary) {
      this.hashes = hashes;
      this.receipts = new ArrayList<>(hashes.size());
      this.notary = notary;
      assertNotNull(notary);
    }

    @Override
    public Witness call() {
      for (int index = 0, count = hashes.size(); index < count; ++index) {
        var rcpt = notary.witness(hashes.get(index).slice());
        receipts.add(rcpt);
      }
      return this;
    }
  }
  
  
  static class Update implements Callable<Update> {

    final List<Crum> crums;
    final List<Receipt> receipts;
    final Notary notary;
    
    
    Update(Witness witness) {
      this.crums = Lists.map(witness.receipts, Receipt::crum);
      this.receipts = new ArrayList<>(crums.size());
      this.notary = witness.notary;
    }

    @Override
    public Update call() {
      for (var crum : crums) {
        var rcpt = notary.update(crum);
        receipts.add(rcpt);
      }
      return this;
    }
  }
  

  @Test
  public void test1000Concurrent() throws Exception {

    
    final Object label = new Object() { };
    final TimeBinner binner = TimeBinner.EIGTH_SEC;
    final long startUtc = System.currentTimeMillis();
    final int blocksRetained = 100;
    final Random random = new Random(210L);
    final int crumCount = 1000;
    final int sampleIndex = crumCount / 2;
    final int witnessThreads = 3;
    

    final File dir = newMethodRunDir(label);
    
    final String name = method(label);
    
    var out = System.out;
    out.println();
    out.println(name + ":");

    
    List<ByteBuffer> wHashes = new ArrayList<>();
    for (int count = crumCount; count-- > 0; ) {
      byte[] rhash = new byte[Constants.HASH_WIDTH];
      random.nextBytes(rhash);
      wHashes.add(ByteBuffer.wrap(rhash));
    }
    
    
    
    
    var executor = Executors.newFixedThreadPool(witnessThreads);
    
    

    NotaryD notaryD;
    {
      var notary = Notary.incept(
          dir,
          binner,
          startUtc,
          blocksRetained);
      
      notaryD = new NotaryD(notary);
    }

    

    // collect the receipts in parallel..
    
    List<Future<Witness>> witFutures = new ArrayList<>(witnessThreads);
    
    final int chunkSize = crumCount / witnessThreads;
    for (int tIndex = 0; tIndex < witnessThreads - 1; ++tIndex) {
      int subIndex = tIndex * chunkSize;
      var hashes = wHashes.subList(subIndex , subIndex + chunkSize);
      var witness = new Witness(hashes, notaryD);
      witFutures.add(executor.submit(witness));
    }
    {
      var hashes = wHashes.subList(
          (witnessThreads - 1) * chunkSize, crumCount);
      var witness = new Witness(hashes, notaryD);
      witFutures.add(executor.submit(witness));
    }
    
    
    List<Witness> wits = new ArrayList<>(witnessThreads);
    for (var future : witFutures)
      wits.add(future.get());
    
    
    
    long maxUtc = 0;
    for (var wit : wits) {
      final int cc = wit.hashes.size();
      assertEquals(cc, wit.receipts.size());
      long lastUtc = 0;
      for (int index = 0; index < cc; ++index) {
        var rcpt = wit.receipts.get(index);
        assertEquals(wit.hashes.get(index), rcpt.crum().hash());
        assertFalse(rcpt.hasTrail());
        long utc = rcpt.crum().utc();
        assertTrue(lastUtc <= utc);
        lastUtc = utc;
      }
      if (maxUtc < lastUtc)
        maxUtc = lastUtc;
    }
    
    long lastBlockUtcEnd =
        binner.binTime(maxUtc) + binner.duration();
    
    final long commitSlack = 3 * binner.duration();
    
    final long targetCommitUtc = lastBlockUtcEnd + commitSlack;
    
    long now = System.currentTimeMillis();
    
    out.println("commit no.: " + notaryD.blockCount());
    
    if (now < targetCommitUtc)
      Thread.sleep(targetCommitUtc - now);
    
    assertTrue(notaryD.blockCount() > 0);
    
    
    List<Future<Update>> updateFutures = new ArrayList<>(witnessThreads);
    for (var wit: wits) {
      var update = new Update(wit);
      updateFutures.add(executor.submit(update));
    }
    
    List<Update> updates = new ArrayList<>(witnessThreads);
    for (var future : updateFutures)
      updates.add(future.get());
    

    Receipt sample = null;
    int index = 0;
    for (var up : updates) {
      final int cc = up.crums.size();
      assertEquals(cc, up.receipts.size());
      for (int subIndex = 0; subIndex < cc; ++subIndex, ++index) {
        var rcpt = up.receipts.get(subIndex);
        if (index == sampleIndex)
          sample = rcpt;
        
        // following fails once in a blue moon..
        // prolly not enough commit slack, or too few blocks retained (?)
        // add more info next time it occurs..
        
        assertTrue(rcpt.hasTrail(), "on block [" + rcpt.blockNo() + "]");
        assertEquals(up.crums.get(subIndex), rcpt.crum());
      }
    }

    executor.shutdown();
    
    notaryD.close();
    out.println("sample trail..");
    print(sample.trail());
  }
  

  private void print(Crumtrail trail) {
    JsonPrinter.println(
        new CrumtrailParser(HashEncoding.HEX).toJsonObject(trail));
  }

}
