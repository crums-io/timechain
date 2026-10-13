/*
 * Copyright 2024 Babak Farhang
 */
package io.crums.tc.notary;


import static io.crums.tc.Constants.HASH_WIDTH;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

import io.crums.tc.Crum;
import io.crums.util.collections.Lists;

/**
 * In-memory (or memory-mapped) {@linkplain CrumMerkleTree} implementation.
 * 
 */
public class CrumTreeBuffer extends CrumMerkleTree {
  
  /**
   * Offset at which the Merkle tree nodes begin.
   * The head data (what's before this offset) presently
   * contains only the number of leaves (derived from the
   * crums's {@linkplain Crum#witnessHash() witness hash}es)
   * in the Merkle tree. So it's the size of an {@code int} (4).
   */
  public final static int NODE_DATA_HEAD = 4;
  
  private final ByteBuffer data;

  

  public CrumTreeBuffer(ByteBuffer data) {
    this(data, NotaryLog.NULL);
  }
  
  public CrumTreeBuffer(ByteBuffer data, NotaryLog log) {
    super(data.getInt(data.position()), log);
    
    // slice an exact-size, read-only view
    {
      int tally = NODE_DATA_HEAD;
      tally += idx().totalCount() * HASH_WIDTH;
      final int crums = idx().count();
      tally += crums * Crum.DATA_SIZE;
      
      if (tally > data.remaining())
        throw new IllegalArgumentException(
            "Underflow. Expected " + tally +
            " bytes; actual " + data.remaining());
      
      data = data.slice();
      if (!data.isReadOnly())
        data = data.asReadOnlyBuffer();
      
      if (tally < data.remaining())
        data = data.limit(tally).slice();
    }
    
    this.data = data;
  }

  protected CrumTreeBuffer(CrumTreeBuffer copy) {
    super(copy, copy.log);
    this.data = copy.data;
  }

  
  
  
  
  
  @Override
  public List<Crum> crums() {
    return new Lists.RandomAccessList<Crum>() {
      
      @Override
      public Crum get(int index) {
        Objects.checkIndex(index, size());
        int crumsZeroOffset = NODE_DATA_HEAD + idx().totalCount() * HASH_WIDTH;
        int offset = crumsZeroOffset + index * Crum.DATA_SIZE;
        return new Crum(
            data.slice().position(offset).limit(offset + Crum.DATA_SIZE));
      }

      @Override
      public int size() {
        return idx().count();
      }
    };
  }

  // I *hate this copying, but note it mostly happens
  // on constructing (small) merkle proofs
  @Override
  public byte[] data(int level, int index) {
    byte[] nodeData = new byte[HASH_WIDTH];
    int offset = NODE_DATA_HEAD + idx().serialIndex(level, index) * HASH_WIDTH;
    data.slice().position(offset).get(nodeData);
    return nodeData;
  }

}




