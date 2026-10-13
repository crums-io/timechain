/*
 * Copyright 2020-2024 Babak Farhang
 */
package io.crums.tc;


import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;

import io.crums.util.api.Serial;


/**
 * A timestamp, witness to a hash. Instances are immutable.
 * This is a 2-tuple: a hash, and a UTC time.
 */
public class Crum extends HashUtc implements Comparable<Crum>, Serial {
  
  
  public static Crum newSearchKey(byte[] hash) {
    return new Crum(hash, Crum.INCEPTION_UTC);
  }
  public static Crum newSearchKey(ByteBuffer hash) {
    return new Crum(hash, Crum.INCEPTION_UTC);
  }
  


  /**
   * Creates a new instance from its serial form.
   * 
   * @param data the serial form. Not copied; <em>do not modify!</em>
   * 
   * @see HashUtc#serialForm()
   */
  public Crum(ByteBuffer data) {
    super(data);
  }

  /**
   * Creates a new instance using the given hash and time.
   * 
   * @param hash  of length 32; defensively copied
   * @param utc   UTC time
   */
  public Crum(byte[] hash, long utc) {
    super(hash, utc);
  }

  /**
   * Creates a new instance using the given hash and time.
   * 
   * @param hash  with 32 remaining bytes; defensively copied
   * @param utc   UTC time
   */
  public Crum(ByteBuffer hash, long utc) {
    super(hash, utc);
  }

  /**
   * Instances are ordered firstly by hash; secondly in time. Consistent with equals.
   */
  @Override
  public final int compareTo(Crum o) {
    int comp = HASH_COMPARATOR.compare(this, o);
    return comp == 0 ? Long.compare(utc(), o.utc()) : comp;
  }
  
  
  /**
   * Returns the SHA-256 hash of the crum's {@linkplain #serialForm() serial}
   * byte representation.
   * 
   * @return  32-byte hash
   */
  public final byte[] witnessHash() {
    return witnessHash(Constants.DIGEST.newDigest());
  }
  
  /**
   * Returns the SHA-256 hash of the crum's {@linkplain #serialForm() serial}
   * byte representation. {@code MessageDigest}'s are expensive, so this
   * method offers a way to reused them.
   * 
   * @param digest SHA-256 digester (argument is not checked)
   * @return  32-byte hash
   */
  public final byte[] witnessHash(MessageDigest digest) {
    digest.reset();
    digest.update(serialForm());
    return digest.digest();
  }
  
  
  @Override
  public final ByteBuffer serialize() {
    return serialForm();
  }
  @Override
  public final int serialSize() {
    return DATA_SIZE;
  }
  
  @Override
  public final ByteBuffer writeTo(ByteBuffer out) throws BufferOverflowException {
    return out.put(serialForm());
  }
  
  

}




