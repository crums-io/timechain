/*
 * Copyright 2024 Babak Farhang
 */
package io.crums.tc.notary;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

import io.crums.util.io.channels.ChannelUtils;
import io.crums.tc.ChainParams;
import io.crums.tc.Constants;
import io.crums.tc.Crum;
import io.crums.tc.notary.except.NotaryException;
import io.crums.util.TaskStack;
import io.crums.util.mrkl.FixedLeafBuilder;
import io.crums.util.mrkl.FixedLeafTree;

/**
 * 
 */
public class CrumTreeBuilder {

  
  private final TreeMap<ByteBuffer, Crum> crums = new TreeMap<>();
  private final ChainParams chainParams;
  private final long blockNo;
  private final NotaryLog log;
  
  
  public CrumTreeBuilder(ChainParams chainParams, long blockNo, NotaryLog log) {
    this.log = Objects.requireNonNull(log);
    this.chainParams = Objects.requireNonNull(chainParams);
    this.blockNo = blockNo;
    if (blockNo < 1)
      throw new IllegalArgumentException("blockNo: " + blockNo);
  }
  
  
  public synchronized void add(Crum crum) throws NotaryException {
    // sanity check the crum belongs to the block
    {
      long utc = crum.utc();
      if (chainParams.blockNoForUtc(utc) != blockNo) {
        var nx = new NotaryException(
            crum + " does not belong in block [" + blockNo + "]");
        log.fatal(nx);
        throw nx;
      }
    }
    
    ByteBuffer hash = crum.hash();
    Crum prev = crums.put(hash, crum);
    if (prev == null)
      return;
    
    if (prev.utc() < crum.utc()) {
      crums.put(hash, prev);
      log.warning(
          "crum conflict resolved by rollback: " + crum.hashHex() +
          " " + prev.utc() + " <-- " + crum.utc());
    } else if (prev.utc() > crum.utc()) {
      log.warning(
          "crum conflict resolved by override: " + crum.hashHex() +
          " " + prev.utc() + " --> " + crum.utc());
    } else {
      log.warning("duplicate crum ignored: " + crum);
    }
  }
  
  

  /** Returns the number of crums added. */
  public synchronized int count() {
    return crums.size();
  }
  
  /** Returns the first crum, if any; {@code null} o.w. */
  public synchronized Crum first() {
    var entry = crums.firstEntry();
    return entry == null ? null : entry.getValue();
  }
  
  
  
  public synchronized void addAll(Stream<Crum> crumStream) {
    crumStream.forEach(crum -> add(crum));
  }
  
  
  /**
   * Builds the merkle tree, writes it to the given target
   * file, and returns the root hash of the tree.
   * 
   * @param target  file to write to (a staging file)
   * @return read-only buffer
   */
  public synchronized ByteBuffer buildToTarget(File target) {
    if (target.isDirectory())
      throw new IllegalArgumentException(
          "target file is a directory: " + target);
    
    // note the crum count
    final int cc = crums.size();
    if (cc < 2)
      throw new IllegalStateException(
          "fewer than 2 (" + cc + ") crums added");
    
    var builder = new FixedLeafBuilder(Constants.HASH_ALGO, false);
    
    byte[] serialized = new byte[Crum.DATA_SIZE];
    
    for (var crum : crums.values()) {
      crum.serialForm().get(serialized);
      byte[] crumHash = builder.hash(serialized);
      builder.add(crumHash);
    }
    
    var baseTree = (FixedLeafTree) builder.build();
    assert baseTree.idx().count() == cc;
    
    try (var closer = new TaskStack()) {
      
      @SuppressWarnings("resource")
      var file = new FileOutputStream(target).getChannel();
      closer.pushClose(file);
      
      var ccBuf = ByteBuffer.allocate(4).putInt(cc).flip();
      ChannelUtils.writeRemaining(file, ccBuf);
      ChannelUtils.writeRemaining(file, baseTree.dataBlock());
      
      for (var crum : crums.values())
        ChannelUtils.writeRemaining(file, crum.serialForm());
      
      byte[] rootHash = baseTree.hash();
      return ByteBuffer.wrap(rootHash).asReadOnlyBuffer();
      
    } catch (IOException iox) {
      var nx = new NotaryException(
          "on writing to merkle tree to " + target + ", detail: " +
          iox.getMessage(), iox);
      log.fatal(nx);
      throw nx;
    }
  }
      

}


















