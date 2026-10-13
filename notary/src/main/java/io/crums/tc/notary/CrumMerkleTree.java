/*
 * Copyright 2024 Babak Farhang
 */
package io.crums.tc.notary;


import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import io.crums.tc.CargoProof;
import io.crums.tc.Constants;
import io.crums.tc.Crum;
import io.crums.tc.notary.except.CrumNotFoundException;
import io.crums.util.collections.Lists;
import io.crums.util.mrkl.Tree;

/**
 * 
 */
public abstract class CrumMerkleTree extends Tree {
  
  protected final NotaryLog log;

  /**
   * @param leaves no. of hashes at bottom layer
   */
  protected CrumMerkleTree(int leaves, NotaryLog log) {
    super(leaves, Constants.HASH_ALGO);
    this.log = Objects.requireNonNull(log);
  }

  protected CrumMerkleTree(Tree copy, NotaryLog log) {
    super(copy);
    this.log = Objects.requireNonNull(log);
  }
  

  

  @Override
  public final int leafWidth() {
    return Constants.HASH_WIDTH;
  }
  
  
  public CargoProof proof(Crum crum) throws CrumNotFoundException {
    List<Crum> crums = crums();
    var hashes = Lists.map(crums, c -> c.hash());
    int index = Collections.binarySearch(hashes, crum.hash());
    if (index < 0)
      throw new CrumNotFoundException(
          "failed to find " + crum + " in merkle tree");
    
    Crum recordCrum = crums.get(index);
    if (recordCrum.utc() != crum.utc())
      checkUnmatchedUtcs(recordCrum, crum);
    
    var vanillaProof = proof(index);
    return new CargoProof(vanillaProof, recordCrum);
  }
  
  
  /**
   * Returns the proof for the given hash, if found;
   * {@code null}, otherwise.
   */
  public CargoProof findProof(ByteBuffer hash) {
    List<Crum> crums = crums();
    var hashes = Lists.map(crums, c -> c.hash());
    int index = Collections.binarySearch(hashes, hash);
    if (index < 0)
      return null;
    
    Crum recordCrum = crums.get(index);
    var vanillaProof = proof(index);
    return new CargoProof(vanillaProof, recordCrum);
  }
  
  
  private void checkUnmatchedUtcs(Crum recordCrum, Crum crum) {
    if (recordCrum.utc() > crum.utc())
      throw new CrumNotFoundException(
          "given crum " + crum + " is before crum of record " +
          recordCrum);
    
    log.warning(
        "race detected in witnessing hash: " + crum.hashHex() +
        " (resolved to earlier UTC of record: " +
        crum.utc() + " --> " + recordCrum.utc() + ")");
  }
  
  
  /**
   * <p>
   * Returns the backing crums. The returned crums are lexicographically
   * ordered by {@linkplain Crum#hash() hash}
   * </p>
   * <p>
   * The returned list may generate a <em>new</em> object (crum) on each invocation of
   * {@linkplain List#get(int)} with the <em>same index</em> argument. Treat these
   * as <em>value objects</em>; do not compare them by reference.
   * </p>
   * 
   * @return ordered, of size &ge; 2
   */
  public abstract List<Crum> crums();

}
