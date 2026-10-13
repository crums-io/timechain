/*
 * Copyright 2024 Babak Farhang
 */
package io.crums.tc;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;

import io.crums.util.api.Serial;

/**
 * Chain parameter settings. Presently contains only 2 pieces of
 * information:
 * 
 * <ol>
 * <li>No. of milliseconds a block spans. This is always a positive
 * power of 2.</li>
 * <li>The starting time of the first block.</li>
 * </ol>
 */
public class ChainParams implements Serial {
  
  
  
  
  /**
   * Creates and returns an instance. Less finicky than constructor.
   * 
   * @return {@code new ChainParams(timeBinner, timeBinner.binTime(utcStart))}
   */
  public static ChainParams forStartUtc(TimeBinner timeBinner, long utcStart) {
    return new ChainParams(timeBinner, timeBinner.binTime(utcStart));
  }
  
  
  /**
   * Size of an instance.
   * (9): 1 byte for the bin exponent, 8 for the inception UTC.
   */
  public final static int BYTE_SIZE = 1 + 8;
  
  private final TimeBinner timeBinner;
  private final long inceptionUtc;
  private final long blockDuration;
  

  /**
   * Creates a new instance. The {@code inceptionUtc} must lie at the
   * beginning at time bin boundary defined by the {@code timeBinner}.
   * I.e. {@code inceptionUtc == timeBinner.binTime(inceptionUtc)}
   * must be {@code true}.
   * 
   * @see #forStartUtc(TimeBinner, long)
   */
  public ChainParams(TimeBinner timeBinner, long inceptionUtc) {
    this.timeBinner = timeBinner;
    this.inceptionUtc = inceptionUtc;
    
    if (inceptionUtc < HashUtc.INCEPTION_UTC)
      throw new IllegalArgumentException(
          "inception UTC impossibly low: " + inceptionUtc);
    if (inceptionUtc > System.currentTimeMillis())
      throw new IllegalArgumentException(
          "inception UTC ahead of system time: " + inceptionUtc);
    if (inceptionUtc != timeBinner.binTime(inceptionUtc))
      throw new IllegalArgumentException(
          "inception UTC does not aligned at time bin boundary: " +
          inceptionUtc);
    
    this.blockDuration = timeBinner.duration();
  }
  
  
  /**
   * <p>Instances are equal if they have the same time binner and
   * inception UTC.</p>
   * {@inheritDoc}
   */
  @Override
  public final boolean equals(Object o) {
    return o == this ||
        o instanceof ChainParams other &&
        equalParams(other);
  }
  

  /** Equality sans {@code Object.equals(..)} formalities. */
  public final boolean equalParams(ChainParams other) {
    return
        inceptionUtc == other.inceptionUtc &&
        timeBinner == other.timeBinner;
  }
  
  
  /**
   * <p>Consistent with {@linkplain #equals(Object)}.</p>
   * {@inheritDoc}
   */
  @Override
  public final int hashCode() {
    return Long.hashCode(inceptionUtc) ^ timeBinner.hashCode();
  }
  
  
  @Override
  public String toString() {
    return
        "[" + timeBinner.binExponent() + ":" + inceptionUtc + "(" +
        timeBinner + ":" + inceptDateString() + ")]";
  }
  
  
  private String inceptDateString() {
    return
        new SimpleDateFormat("yyyy/MM/dd_HH:mm:ss.SSS")
        .format(new Date(inceptionUtc));
  }
  
  
  
  public final TimeBinner timeBinner() {
    return timeBinner;
  }
  
  /**
   * Returns the inception UTC. Equivalently, this is
   * the UTC for the first block in the chain.
   */
  public final long inceptionUtc() {
    return inceptionUtc;
  }
  

  /**
   * Returns the current block no. inferred from system time.
   * The block at the returned no. is actually likely not yet committed
   * to the timechain.
   * 
   * @return {@code blockNoForUtc(System.currentTimeMillis())}
   */
  public final long blockNoNow() {
    return blockNoForUtc(System.currentTimeMillis());
  }
  
  /**
   * Returns the block number the given {@code utc} falls in.
   * 
   * @throws IllegalArgumentException
   *         if {@code utc < inceptionUtc()}
   */
  public final long blockNoForUtc(long utc)
      throws IllegalArgumentException {
    if (utc < inceptionUtc)
      throw new IllegalArgumentException(
          "utc (" + utc + ") < inception UTC (" + inceptionUtc + ")");
    return timeBinner.toBinNo(utc - inceptionUtc) + 1;
  }
  
  
  /**
   * Returns the block number the given {@code utc} falls in. Only
   * positive return values are valid.
   */
  public final long blockNoForUtcUnchecked(long utc) {
    return timeBinner.toBinNo(utc - inceptionUtc) + 1;
  }
  
  
  
  /**
   * Determines whether the given {@code utc} falls in the given
   * {@code blockNo}.
   */
  public final boolean utcInBlock(long utc, long blockNo) {
    return
        utc >= inceptionUtc &&
        timeBinner.toBinNo(utc - inceptionUtc) + 1 == blockNo;
    
  }
  
  /**
   * Returns the starting (smallest) UTC value for the given
   * block no. The last (largest) UTC value in the block is
   * the returned value plus the {@linkplain #blockDuration()},
   * less one.
   * 
   */
  public final long utcForBlockNo(long block) {
    // blocks no.s start from 1, not zero
    return inceptionUtc + (block - 1) * blockDuration;
  }
  


  /**
   * Returns the block duration (span) in milliseconds.
   * 
   * @return a power of 2
   */
  public final long blockDuration() {
    return blockDuration;
  }
  
  
  

  @Override
  public int serialSize() {
    return BYTE_SIZE;
  }

  @Override
  public ByteBuffer writeTo(ByteBuffer out) throws BufferOverflowException {
    out.put((byte) timeBinner.shift);
    out.putLong(inceptionUtc);
    return out;
  }
  
  
  /**
   * Loads and returns an instance, reading the given buffer.
   * 
   * @param data  advanced by {@linkplain ChainParams#BYTE_SIZE} bytes
   *              on return
   * @return      deserialized instance
   */
  public static ChainParams load(ByteBuffer data) {
    if (data.remaining() < BYTE_SIZE)
      throw new IllegalArgumentException(data.toString());
    int binExponent = data.get();
    TimeBinner timeBinner = TimeBinner.forExponent(binExponent);
    long inceptionUtc = data.getLong();
    return new ChainParams(timeBinner, inceptionUtc);
  }

}
