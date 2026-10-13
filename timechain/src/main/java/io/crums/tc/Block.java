/*
 * Copyright 2024 Babak Farhang
 */
package io.crums.tc;


import static io.crums.tc.Constants.HASH_WIDTH;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

import io.crums.util.api.Serial;

/**
 * Serial (on-disk) representation of a time chain block.
 * 
 * @see #serialize()
 */
public final class Block implements Serial {
  
  
  /**
   * Returns a block with no cargo. It's crum-count will be zero.
   * 
   * @param blockHash 32-byte block hash
   * 
   * @return an empty block with zero crum-count, and sentinel (zeroes)
   *         hash as cargo
   */
  public static Block newEmptyBlock(ByteBuffer blockHash) {
    if (blockHash.remaining() != HASH_WIDTH)
      throw new IllegalArgumentException(
          "illegal hash width: " + blockHash);
    ByteBuffer blockData = ByteBuffer.allocate(BYTE_SIZE);
    blockData.put(blockData).position(0);
    return new Block(blockData, null, null);
  }
  
  /**
   * Fixed byte size (64).
   */
  public final static int BYTE_SIZE = HASH_WIDTH * 2;
  
  private final ByteBuffer blockData;

  
  
  
  
  private Block(
      ByteBuffer blockData, Object disambig1, Object disambig2) {
    this.blockData = blockData;
  }
  
  
  
  public Block(ByteBuffer blockHash, ByteBuffer cargoHash) {
    if (blockHash.remaining() != HASH_WIDTH)
      throw new IllegalArgumentException("blockHash: " + blockHash);
    if (cargoHash.remaining() != HASH_WIDTH)
      throw new IllegalArgumentException("cargoHash: " + cargoHash);
    this.blockData =
        ByteBuffer.allocate(BYTE_SIZE)
        .put(blockHash).put(cargoHash)
        .flip().asReadOnlyBuffer();
    
  }
  

  /**
   * Creates a new instnce with the given data buffer.
   * 
   * @param data    exactly {@linkplain #BYTE_SIZE} remaining bytes. Sliced.
   * 
   * @see #Block(ByteBuffer, boolean)
   */
  public Block(ByteBuffer data) {
    this(data, true);
  }
  
  /**
   * Creates a new instnce with the given data buffer.
   * 
   * @param data    exactly {@linkplain #BYTE_SIZE} remaining bytes
   * @param slice   if {@code true}, the {@code data} buffer is sliced;
   *                otherwise, <em>caller agrees not to change buffer's
   *                positional state</em>.
   */
  public Block(ByteBuffer data, boolean slice) {
    if (data.remaining() != BYTE_SIZE)
      throw new IllegalArgumentException("remaining bytes: " + data);
    
    this.blockData = slice || data.position() != 0 ? data.slice() : data;
  }
  
  
  private void assertSentinelCargo(ByteBuffer cargoHash) {
    if (!cargoHash.equals(Constants.DIGEST.sentinelHash()))
      throw new IllegalArgumentException(
          "cargo hash must be sentinel hash (zeroes) when cargo count is zero");
  }
  
  
  
  /**
   * Creates a new instance. On return the given buffers have no remaining bytes.
   * 
   * @param blockHash   exactly {@linkplain #BYTE_SIZE} remaining bytes
   * @param cargoHash   exactly {@linkplain #BYTE_SIZE} remaining bytes
   * @param cargoCount  number of crums recorded in the cargo hash (non-negative)
   */
  public Block(ByteBuffer blockHash, ByteBuffer cargoHash, int cargoCount) {
    if (blockHash.remaining() != HASH_WIDTH)
      throw new IllegalArgumentException(
          "remaining bytes in block hash: " + blockHash);
    if (cargoHash.remaining() != HASH_WIDTH)
      throw new IllegalArgumentException(
          "remaining bytes in cargo hash: " + cargoHash);
    
    if (cargoCount == 0)
      assertSentinelCargo(cargoHash);
    else if (cargoCount < 1)
      throw new IllegalArgumentException(
          "negative cargo count: " + cargoCount);
    
    ByteBuffer data = ByteBuffer.allocate(BYTE_SIZE);
    data.put(blockHash).put(cargoHash);
    
    this.blockData = data.flip();
  }
  
  
  
  
  
  
  
  
  /**
   * Returns the block hash.
   */
  public ByteBuffer blockHash() {
    return blockData.asReadOnlyBuffer().limit(HASH_WIDTH);
  }
  
  
  /**
   * Returns a hash of the set of crums witnessed
   * in this block. By convention, if no crums were witnessed,
   * then this is the sentinel hash (all zeroes).
   */
  public ByteBuffer cargoHash() {
    var view = blockData.asReadOnlyBuffer();
    return view.position(HASH_WIDTH).slice();
  }
  
  
  
  
  /**
   * Returns a read-only view of the block data.
   */
  @Override
  public ByteBuffer serialize() {
    return blockData.asReadOnlyBuffer();
  }
  

  /**
   * @return {@link #BYTE_SIZE}
   */
  @Override
  public int serialSize() {
    return BYTE_SIZE;
  }

  @Override
  public ByteBuffer writeTo(ByteBuffer out) throws BufferOverflowException {
   return out.put(serialize());
  }

}
