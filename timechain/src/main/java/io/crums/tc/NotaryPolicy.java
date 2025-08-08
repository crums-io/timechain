/*
 * Copyright 2024 Babak Farhang
 */
package io.crums.tc;



/**
 * Timechain / notary settings that concern an end user. 
 */
public class NotaryPolicy {
  
  
  public final static int DEFAULT_BLOCKS_SEARCHED = 3;

  
  public final static int MIN_BLOCKS_RETAINED = 3;
  
  
  public final static int MIN_BLOCK_COMMIT_LAG = 2;
  

  private final ChainParams params;
  
  private final int blocksRetained;
  
  private final int blockCommitLag;
  
  private final int blocksSearched;
  
  
  
  
  /**
   * Constructs an instance with reasonable defaults. The
   * {@link #blockCommitLag()} setting: it is the greater of
   * {@linkplain #MIN_BLOCK_COMMIT_LAG the minimum} and half the block
   * duration.
   * 
   * @see #DEFAULT_BLOCKS_SEARCHED
   * @see #NotaryPolicy(ChainParams, int, int)
   */
  public NotaryPolicy(ChainParams params, int blocksRetained) {
    this(
        params,
        blocksRetained,
        Math.max((int) params.blockDuration() / 2, MIN_BLOCK_COMMIT_LAG),
        DEFAULT_BLOCKS_SEARCHED);
  }
  

  
  /**
   * Constructs an instance, computing a reasonable default for the
   * {@link #blockCommitLag()} setting: it is the greater of
   * {@linkplain #MIN_BLOCK_COMMIT_LAG the minimum} and half the block
   * duration.
   * 
   * @param params
   * @param blocksRetained
   * @param blocksSearched
   */
  public NotaryPolicy(
      ChainParams params, int blocksRetained, int blocksSearched) {
    this(
        params,
        blocksRetained,
        Math.max((int) params.blockDuration() / 2, MIN_BLOCK_COMMIT_LAG),
        blocksSearched);
  }
  

  
  /**
   * Full constructor.
   * 
   * @param params          see {@linkplain #chainParams()}
   * @param blocksRetained  see {@linkplain #blocksRetained()}
   * @param blockCommitLag  see {@linkplain #blockCommitLag()}
   * @param blocksSearched  see {@linkplain #blocksSearched()}
   */
  public NotaryPolicy(
      ChainParams params,
      int blocksRetained, int blockCommitLag, int blocksSearched) {
    this.params = params; // (null checked below)
    this.blocksRetained = blocksRetained;
    this.blockCommitLag = blockCommitLag;
    this.blocksSearched = blocksSearched;
    
    if (blocksRetained < MIN_BLOCKS_RETAINED)
      throw new IllegalArgumentException(
          "blocksRetained (" + blocksRetained + ") < MIN_BLOCKS_RETAINED (" +
          MIN_BLOCKS_RETAINED + ")");
    if (blockCommitLag < MIN_BLOCK_COMMIT_LAG)
      throw new IllegalArgumentException(
          "blockCommitLag (" + blockCommitLag +
          ") <  MIN_BLOCK_COMMIT_LAG (" +
          MIN_BLOCK_COMMIT_LAG + ")");
    
    // (params null check too)
    if (blockCommitLag >=
        (blocksRetained / 3) * params.timeBinner().duration())
      
      throw new IllegalArgumentException(
          "blockCommitLag (" + blockCommitLag +
          ")  too large for given blocksRetained (" +
          blocksRetained + ") and binner " + params.timeBinner());
    
    if (blocksSearched < 0)
      throw new IllegalArgumentException("blocksSearched: " + blocksSearched);
          
  }
  
  
  /** Copy / promotion constructor. */
  protected NotaryPolicy(NotaryPolicy policy) {
    this.params = policy.params;
    this.blocksRetained = policy.blocksRetained;
    this.blockCommitLag = policy.blockCommitLag;
    this.blocksSearched = policy.blocksSearched;
  }

  

  /**
   * Time chain block boundaries, and 1st block UTC.
   */
  public final ChainParams chainParams() {
    return params;
  }
  
  
  /**
   * Number of cargo blocks retained after the last committed block.
   * @return &ge; {@linkplain #MIN_BLOCKS_RETAINED}
   */
  public final int blocksRetained() {
    return blocksRetained;
  }

  
  /**
   * Number of milliseconds after which a cargo block is considered
   * committed. This, in turn, establishes the earliest time a
   * cargo block can be built.
   * <p>
   * Note, races are OK under our model. That is, a notary will occasionally
   * return a crum receipt for which no trail can later be found: the larger
   * this setting, the less likely that occurs.
   * </p>
   * 
   * @return &ge; {@link #MIN_BLOCK_COMMIT_LAG}
   */
  public final int blockCommitLag() {
    return blockCommitLag;
  }
  
  
  
  /**
   * Number of logical blocks searched on witnessing a new hash.
   * When a user submits a hash to be
   * {@linkplain NotaryService#witness(java.nio.ByteBuffer) witness}ed,
   * the notary looks back thru a fixed number (<em>this no.</em>)
   * of recent "cargo" blocks to check whether it has already seen
   * the hash.
   * <p>
   * Note, <em>logical block</em> here is just a block no.: there may
   * or may not exist any witnessed hashes for that block no.
   * </p>
   * 
   * @return &ge; 0 (zero means no blocks are searched)
   */
  public final int blocksSearched() {
    return blocksSearched;
  }



  /** Equality sans {@code Object.equals(..)} formalities. */
  public final boolean equalPolicy(NotaryPolicy other) {
    return
        params.equalParams(other.params) &&
        blockCommitLag == other.blockCommitLag &&
        blocksRetained == other.blocksRetained &&
        blocksSearched == other.blocksSearched;
  }



  /**
   * Returns the time remaining (in millis) to commit the given
   * block no.
   * 
   * @param blockNo &ge; 1
   * 
   * @return non-negative, i.e. if zero, likely committed
   */
  public final long millisToCommit(long blockNo) {
    return millisToCommit(blockNo, System.currentTimeMillis());
  }


  /**
   * Returns the time remaining (in millis) to commit the given
   * block no.
   * 
   * @param blockNo &ge; 1
   * @param now     UTC time in millis, usually now
   * 
   * @return non-negative, i.e. if zero, likely committed
   */
  public final long millisToCommit(long blockNo, long now) {
    if (blockNo < 1L)
      throw new IllegalArgumentException("blockNo: " + blockNo);

    long blockCommitUtc =
        params.utcForBlockNo(blockNo + 1) + blockCommitLag;

    return Math.max(0L, blockCommitUtc - now);
  }


  /**
   * Returns the TTL for the given cargo block no. in millis.
   * 
   * @param blockNo &ge; 1
   * @return non-negative
   */
  public final long cargoTTL(long blockNo) {
    final long now = System.currentTimeMillis();
    final long blockNoNow = params.blockNoForUtc(now);
    final long blockDiff = blockNoNow - blockNo;
    if (blockDiff <= 0)
      return -1L;
    final long oldestCargoNo = blockNoNow - blocksRetained + 1;
    if (blockNo < oldestCargoNo)
      return 0;
    else
      return
        (blockNo - oldestCargoNo) * params.blockDuration() +
        millisToCommit(blockNoNow, now);
  }
  
  
}















