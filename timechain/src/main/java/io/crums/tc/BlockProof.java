/*
 * Copyright 2024 Babak Farhang
 */
package io.crums.tc;


import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;

import io.crums.io.Serial;
import io.crums.sldg.HashConflictException;
import io.crums.sldg.MemoPathPack;
import io.crums.sldg.Path;
import io.crums.sldg.PathPack;

/**
 * Proof that a block belongs in a chain. This uses the skip ledger
 * data structure / commitment scheme.
 */
public class BlockProof implements Serial {
  
  private final ChainParams params;
  private final Path chainState;
  
  // TBD field.. the plan is to tombstone chain state hashes in 
  // other chains: together they weave a network of causal structures.
  // A chain then will be able to package proofs about its validity
  // relative to other chains. 
  private final Optional<ChainId> chainId;
  
  
  /** Creates an instance with no ID. */
  public BlockProof(ChainParams params, Path chainState) {
    this(params, chainState, Optional.empty());
  }

  /**
   * Full constructor.
   * 
   * @param chainId TBD, empty by default
   */
  public BlockProof(
      ChainParams params, Path chainState, Optional<ChainId> chainId) {
    this.params = Objects.requireNonNull(params);
    this.chainState = Objects.requireNonNull(chainState);
    this.chainId = Objects.requireNonNull(chainId);
    
  }


  /**
   * Returns an abbreviated version of this block proof connecting the given
   * block to the highest block no in this proof.
   * 
   * @param bn  block no
   * @return    empty, if the given block no. is not contained in this
   *            block proof
   */
  public Optional<BlockProof> forBlockNo(long bn) {
    return forBlockNo(bn, false);
  }



  /**
   * Returns an abbreviated version of this block proof connecting the given
   * block to the highest block no in this proof.
   * 
   * @param bn            block no
   * @param withLineage   if {@code true}, then the returned proof (if any)
   *                      will include a path from the first known block in this
   *                      proof to the block numbered {@code bn}.
   * @return    empty, if the given block no. is not contained in this
   *            block proof
   */
  public Optional<BlockProof> forBlockNo(long bn, boolean withLineage) {
    if (!chainState.hasRow(bn))
      return Optional.empty();
    if (isCompressed())
      return Optional.of(this);
    Path abbreviatedState;
    if (withLineage)
      abbreviatedState = chainState.skipPath(bn).get();
    else if (bn == blockNo())
      abbreviatedState = chainState.skipPath();
    else
      abbreviatedState = chainState.skipPath(true, bn, chainState.hi()).get();
    
    return Optional.of(
        abbreviatedState.length() == chainState.length() ?
            this :
            new BlockProof(params, abbreviatedState, chainId));
  }



  /**
   * Tells whether the {@linkplain #chainState() chain state} is compressed.
   * 
   * @return {@code chainState().isCompressed()}
   */
  public final boolean isCompressed() {
    return chainState.isCompressed();
  }



  /**
   * Returns a {@linkplain #isCompressed() compressed} version of this instance;
   * if already compressed, then {@code this} is returned.
   */
  public final BlockProof compress() {
    return
        isCompressed() ?
            this :
            new BlockProof(params, chainState.compress(), chainId);
  }


  /**
   * Determines whether this instances uses <em>condensed</em> information
   * about its hash pointers. Usually (but not always), to be
   * {@linkplain #isCompressed() compressed}, an instance must also be
   * condensed.
   * 
   * @return {@code chainState().isCondensed()}
   */
  public final boolean isCondensed() {
    return chainState.isCondensed();
  }


  public final BlockProof appendTail(BlockProof tail) {
    if (!tail.params.equalParams(params))
      throw new IllegalArgumentException("chain params mismatch: " + tail.params);
    return appendTail(tail.chainState);
  }


  /**
   * Appends the given timechain <em>tail</em> blocks to this blockproof
   * and returns the result.
   * 
   * @param tailBlocks  <em>must</em> reference the hash of this instance's last
   *                    block
   * @return  a new block proof
   * 
   * @see Path#appendTail(Path)
   * @see #forBlockNo(long)
   */
  public final BlockProof appendTail(Path tailBlocks)
      throws HashConflictException {

    Path newStatePath = chainState.appendTail(tailBlocks);
    return new BlockProof(params, newStatePath);
  }



  /**
   * Returns the highest block no. contained in both this and the
   * {@code other} block proof.
   * 
   * @param other   block proof from the same chain this instance is from
   * 
   * @return  the highest block no, or zero, if the 2 instances do not
   *          intersect
   * 
   * @throws HashConflictException if the hashes at the highest common block no.s
   *                               for the 2 instances conflict
   * @throws IllegalArgumentException
   *                    if the 2 instances' {@linkplain #chainParams()} conflict
   * 
   * @see #highestCommonBlockNo(Path)
   */
  public final long highestCommonBlockNo(BlockProof other)
      throws HashConflictException {

    if (!params.equalParams(other.params))
        throw new IllegalArgumentException(
          "chain params mismatch: " + params + " v. " + other.params);

    return highestCommonBlockNo(other.chainState);
  }


  /**
   * Returns the highest block no. contained both in this and the
   * {@code other} hash path proof. The hashes of the blocks at that block no.
   * are checked for consistency. (Since {@code Path} instances are validated
   * on construction, we needn't validate the hash of the lower numbered blocks
   * in the proof.)
   * 
   * <h4>Meaning of Return Value</h4>
   * <p>
   * The return value means we <em>proved</em> the 2 block proofs are in
   * agreement from the genesis block [1] to the block numbered the return
   * value.
   * </p><p>
   * <em>This property is symmetric</em>. I.e.
   * {@code a.highestCommonBlockNo(b) == b.highestCommonBlockNo(a)} is always
   * {@code true}.
   * </p>
   * 
   * @param other   block path proof from the same chain this instance is from
   * 
   * @return  the highest block no, or zero, if the 2 instances do not
   *          intersect
   * 
   * @throws HashConflictException if the hashes at the highest known block no.s
   *                               for the 2 instances conflict
  //  * @see #extendTarget(long, BlockProof)
   */
  public final long highestCommonBlockNo(Path other)
      throws HashConflictException {

    return chainState.highestCommonFullNo(other);
  }



  
  
  /**
   * Returns the last block no.
   */
  public final long blockNo() {
    return chainState.hiRowNumber();
  }
  
  /**
   * Returns the UTC for the start of the last block
   */
  public final long blockUtc() {
    return params.utcForBlockNo(blockNo());
  }
  
  
  /**
   * Returns the chain parameters. This determines the time
   * boundaries each block represents.
   */
  public final ChainParams chainParams() {
    return params;
  }
  
  
  /**
   * A skip ledger path from a lower block no. (usually the first)
   * to a higher block no. (typically the last) in the time chain.
   * The maximum length of this path [of hash proofs] is of
   * <strong>O(</strong>{@code log(blockCount())}<strong>)</strong>.
   */
  public final Path chainState() {
    return chainState;
  }
  
  
  public Optional<ChainId> getChainId() {
    return chainId;
  }


  public final TimeBinner timeBinner() {
    return params.timeBinner();
  }

  @Override
  public int serialSize() {
    return params.serialSize() + chainState.pack().serialSize();
  }
  
  
  /**
   * 
   */
  @Override
  public final boolean equals(Object o) {
    return o == this ||
        o instanceof BlockProof bproof &&
        chainState.last().equals(bproof.chainState().last()) &&
        chainState.rowNumbers().equals(bproof.chainState.rowNumbers());
  }
  
  
  /**
   * Consistent with {@linkplain #equals(Object)}, but expensive. Avoid.
   */
  @Override
  public final int hashCode() {
    return
        chainState.rowNumbers().hashCode() ^
        chainState.last().hash().hashCode();
  }
  
  
  

  /**
   * @see #load(ByteBuffer)
   */
  @Override
  public ByteBuffer writeTo(ByteBuffer out) throws BufferOverflowException {
    params.writeTo(out);
    chainState.pack().writeTo(out);
    return out;
  }
  
  
  /**
   * Loads and returns a memo-ized block proof instance.
   * 
   * @return {@code load(in, true)}
   */
  public static BlockProof load(ByteBuffer in) {
    return load(in, true);
  }
  
  
  /**
   * Loads and returns an instance, reading the given buffer.
   * 
   * @param precompute  if {@code true} the returned instance's
   *                    hash computations are memo-ised (faster to use)
   * @return            deserialized instance
   */
  public static BlockProof load(ByteBuffer in, boolean precompute) {
    var params = ChainParams.load(in);
    Path chainState;
    {
      var pack = PathPack.load(in);
      if (precompute)
        pack = new MemoPathPack(pack);
      chainState = pack.path();
    }
    return new BlockProof(params, chainState);
  }

}


