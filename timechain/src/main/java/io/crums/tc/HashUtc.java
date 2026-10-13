/*
 * Copyright 2020-2024 Babak Farhang
 */
package io.crums.tc;


import static io.crums.tc.Constants.HASH_WIDTH;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.Date;

import io.crums.util.api.Base64_32;
import io.crums.util.IntegralStrings;

/**
 * A 32 byte hash, plus an 8 byte <code>long</code> representing a witness time.
 */
public class HashUtc {
  
  /**
   * Compares 2 instances solely by {@linkplain #hash() hash}.
   */
  public final static Comparator<HashUtc> HASH_COMPARATOR = new Comparator<>() {
    @Override
    public int compare(HashUtc a, HashUtc b) {
      for (int index = 0; index < HASH_WIDTH; ++index) {
        int comp = Byte.compare(a.data.get(index), b.data.get(index));
        if (comp != 0)
          return comp;
      }
      return 0;
    }
  };


  /** Compares 2 instances solely by {@linkplain #utc() utc}. */
  public final static Comparator<HashUtc> UTC_COMPARATOR =  new Comparator<>() {
    @Override
    public int compare(HashUtc a, HashUtc b) {
      return Long.compare(a.utc(), b.utc());
    }
  };
  
  /**
   * Version 2 concept date. We're ditching our existing data, so this'll be okay.
   * A claim to a hash witnessed in the form of a crum before this date (April 6, 2022 12:00:00 AM GMT) is
   * nonsense. The constructor disallows it.
   * 
   * @see #RUN_MAX_UTC
   */
  public final static long INCEPTION_UTC = 1712361600000L; // 1712361600
  
  /**
   * As a sanity check on data, the maximum UTC is bounded by 100 years since startup.
   * (If you don't restart the app once a century, it will crash.)
   * 
   * @see #INCEPTION_UTC
   */
  public final static long RUN_MAX_UTC = System.currentTimeMillis() + 100L * 365 * 24 * 3600 * 1000;
  
  /**
   * Data size is {@linkplain Constants#HASH_WIDTH} + {@linkplain Long#BYTES}.
   */
  public final static int DATA_SIZE = HASH_WIDTH + 8;
  
  
  
  /**
   * Serializes the given hash/utc tuple to the given <code>out</code> buffer. The point of this method
   * is to centralize serialization/deserializaton format (since it's changed).
   * 
   * @param hash {@linkplain Constants#HASH_WIDTH} bytes long
   * @param utc (not bounds checked)
   * @param out assumed to have remaining bytes. On return, assuming good
   *  arguments, the position is advanced {@linkplain #DATA_SIZE} bytes.
   *  
   *  @return the <code>out</code> argument (unflipped)
   * 
   * @see #HashUtc(ByteBuffer) constructor for <em>deserialization</em>.
   * @see #writeToBuffer(ByteBuffer, long, ByteBuffer)
   */
  public static ByteBuffer writeToBuffer(byte[] hash, long utc, ByteBuffer out) {
    if (hash.length != Constants.HASH_WIDTH)
      throw new IllegalArgumentException("hash.length: " + hash.length);
    out.put(hash).putLong(utc);
    return out;
  }
  

  /**
   * Serializes the given hash/utc tuple to the given <code>out</code> buffer. The point of this method
   * is to centralize serialization/deserializaton format (since it's changed).
   * 
   * @param hash {@linkplain Constants#HASH_WIDTH} remaning bytes
   * @param utc (not bounds checked)
   * @param out assumed to have remaining bytes. On return, assuming good
   *  arguments, the position is advanced {@linkplain #DATA_SIZE} bytes.
   *  
   *  @return the <code>out</code> argument (unflipped)
   * 
   * @see #HashUtc(ByteBuffer) constructor for <em>deserialization</em>.
   * @see #writeToBuffer(byte[], long, ByteBuffer)
   */
  public static ByteBuffer writeToBuffer(ByteBuffer hash, long utc, ByteBuffer out) {
    if (hash.remaining() != Constants.HASH_WIDTH)
      throw new IllegalArgumentException("hash.remaining(): " + hash.remaining());
    
    if (out.remaining() < hash.remaining())
      throw new BufferOverflowException();
    out.put(hash.duplicate()).putLong(utc);
    return out;
  }
  
  
  
  

  /**
   * The positional state is <em>never</em> modified, not even momentarily.
   * (If a subclass does, then it'll likely have undefined concurrent behavior.)
   */
  private final ByteBuffer data;
  


  /**
   * Creates a new instance with the given data buffer.
   * 
   * @param data  the caller agrees not to modify contents
   *    
   * @see #serialForm()
   */
  public HashUtc(ByteBuffer data) {
    
    data = data.slice();
    int cap = data.remaining();
    if (cap != DATA_SIZE) {
      if (cap > DATA_SIZE)
        data = data.limit(DATA_SIZE).slice();
      else
        throw new IllegalArgumentException("data remaining: " + cap);
    }
      
    
    
    this.data = data;
    
    // a quick but effective sanity check..
    sanityCheckUtc(utc());
  }
  
  
  /**
   * Creates a new instance with the given fields.
   * 
   * @see #INCEPTION_UTC
   * @see #RUN_MAX_UTC
   */
  public HashUtc(byte[] hash, long utc) {
    
    sanityCheckUtc(utc);
    
    this.data = writeToBuffer(hash, utc, ByteBuffer.allocate(DATA_SIZE)).flip();
  }
  
  
  /**
   * Creates a new instance with the given fields.
   * 
   * @see #INCEPTION_UTC
   * @see #RUN_MAX_UTC
   */
  public HashUtc(ByteBuffer hash, long utc) {

    sanityCheckUtc(utc);
    
    this.data = writeToBuffer(hash, utc, ByteBuffer.allocate(DATA_SIZE)).flip();
  }

  
  /**
   * Bounds checks the given <code>utc</code> as one that could possibly be issued by the service.
   */
  protected final void sanityCheckUtc(long utc) {
    if (utc < INCEPTION_UTC)
      throw new IllegalArgumentException(
          "utc " + utc + " (" + new Date(utc) + ") < project inception utc " + INCEPTION_UTC);
    if (utc > RUN_MAX_UTC)
      throw new IllegalArgumentException(
          "utc " + utc + " (" + new Date(utc) + ") >  RUN_MAX_UTC " + RUN_MAX_UTC);
  }
  
  
  
  /**
   * Returns a read-only buffer whose remaining bytes are the hash. (It's capacity is
   * in fact greater.)
   */
  public ByteBuffer hash() {
    return data.asReadOnlyBuffer().limit(HASH_WIDTH);
  }
  
  /**
   * Returns the {@linkplain #hash() hash} in hexadecimal representation.
   */
  public String hashHex() {
    return IntegralStrings.toHex(hash());
  }
  
  
  /**
   * Returns the {@linkplain #hash() hash} in base64-32 representation.
   */
  public String hash64() {
    return Base64_32.encodeNext32(data.asReadOnlyBuffer());
  }
  
  
  /**
   * Time in UTC milliseconds.
   */
  public long utc() {
    return data.getLong(HASH_WIDTH);
  }
  

  /**
   * Returns the instance's serialized representation as a read-only buffer.
   * 
   * @return a buffer with {@linkplain #DATA_SIZE} remaining bytes.
   * 
   * @see Crum#Crum(ByteBuffer)
   */
  public final ByteBuffer serialForm() {
    return data.asReadOnlyBuffer();
  }
  
  
  
  /**
   * Instances are equal iff their {@linkplain #hash()}s and {@linkplain #utc()}s  are equal.
   * 
   * @see #equalsHashUtc(HashUtc)
   */
  @Override
  public final boolean equals(Object o) {
    return o == this ||  o instanceof HashUtc h && equalsHashUtc(h);
  }
  
  /**
   * Typed {@linkplain #equals(Object)}.
   */
  public final boolean equalsHashUtc(HashUtc o) {
    return HASH_COMPARATOR.compare(this, o) == 0 && utc() == o.utc();
  }
  
  /**
   * Tests if this instance's hash is the same as the other.
   */
  public final boolean hashEquals(HashUtc o) {
    return HASH_COMPARATOR.compare(this, o) == 0;
  }
  
  
  /**
   * Consistent with equals. And efficient. (We lean on the randomness of cryptographic hashes).
   */
  @Override
  public final int hashCode() {
    return data.getInt(0);
  }
  
  /**
   * For debug purposes.
   */
  public String toString() {
    StringBuilder string = new StringBuilder();
    string.append(getClass().getSimpleName()).append("[");
    string.append(IntegralStrings.toHex(hash().limit(8))).append("..+").append(utc()).append("]");
    return string.toString();
  }

}
