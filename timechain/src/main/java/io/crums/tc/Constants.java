/*
 * Copyright 2024 Babak Farhang
 */
package io.crums.tc;

import io.crums.util.hash.Digest;
import io.crums.util.hash.Digests;

/**
 * 
 */
public class Constants {

  /**
   * Software version string. We don't have a <em>protocol</em> version, yet.
   * <p>
   * Note this is duplicated data (it follows the pom version, for now).
   * It also makes its way into picocli version strings.
   * </p>
   */
  public final static String VERSION = "0.1.0";

  /**
   * Default value "User-Agent" request headers take.
   */
  public final static String USER_AGENT = "crum/" + VERSION;
  
  public final static Digest DIGEST = Digests.SHA_256; 

  /**
   * Name of the hashing (digest) algorithm.
   * @see #HASH_WIDTH
   */
  public final static String HASH_ALGO = DIGEST.hashAlgo();
  
  /**
   * Width of the hash, in bytes.
   */
  public final static int HASH_WIDTH = DIGEST.hashWidth();
  
  
  
  public static class Rest {
    
    public final static String API = "/api/";
    

    public final static String POLICY = "policy";
    public final static String POLICY_URI = API + POLICY;
    public final static String WITNESS = "witness";
    public final static String WITNESS_URI = API + WITNESS;
    public final static String UPDATE = "update";
    public final static String UPDATE_URI = API + UPDATE;
    public final static String STATE = "state";
    public final static String STATE_URI = API + STATE;
    
    public final static String UTIL = API + "util/";
    public final static String H_CODEC = "h_codec";
    public final static String H_CODEC_URI = UTIL + H_CODEC;
    public final static String DATE = "date";
    public final static String DATE_URI = UTIL + DATE;
    public final static String VERIFY = "verify";
    public final static String VERIFY_URI = UTIL + VERIFY;

    
    public final static String QS_HASH = "hash";
    public final static String QS_UTC = "utc";
    public final static String QS_BLOCK = "block";
    public final static String QS_ENCODING = "enc";
    /** If {@code true} (the default), then the last block is included in state proof. */
    public final static String QS_LAST = "last";
    public final static String B64 = "b64";
    public final static String HEX = "hex";
    /**
     * If {@code false} (defaults to {@code true}), then the block proof
     * is not compressed, and contains more linkage information to previous
     * blocks.
     */
    public final static String COMPRESS = "compress";



    
    private Rest() { }
  }
  
  
  
  private Constants() { }
  
}
