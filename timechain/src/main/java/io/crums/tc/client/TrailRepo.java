/*
 * Copyright 2024 Babak Farhang
 */
package io.crums.tc.client;



import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import io.crums.util.io.FileUtils;
import io.crums.sldg.HashConflictException;
import io.crums.sldg.Path;
import io.crums.stowkwik.io.HexPathTree;
import io.crums.tc.BlockProof;
import io.crums.tc.Constants;
import io.crums.tc.Crum;
import io.crums.tc.Crumtrail;
import io.crums.util.IntegralStrings;
import io.crums.util.RandomId;


/**
 * Local repo for crumtrails from a <em>single</em> timechain.
 * 
 * <h2>Assumptions</h2>
 * 
 * <ol>
 * <li><em>Sequential Writes.</em></li>
 * <li><em>Uncondensed Crumtrails.</em></li>
 * <li><em>Concurrent Reads.</em> </li>
 * </ol>
 * 
 * <h2>Basic Design</h2>
 * <p>
 * It's maybe easiest to describe in two parts: writing and reading,
 * in that order..
 * </p>
 * <h3>Writing</h3>
 * <p>
 * Crumtrails are keyed by the witnessed hash ({@linkplain Crum#hash()})
 * using a filepath naming scheme called a hex tree (similar to how git
 * does it). When a crumtrail is saved ({@linkplain #add(Crumtrail)}),
 * first the repo's "global" chain [block] proof is updated (up to the
 * block no. of the crumtrail). Barring any hash conflicts in the 1st step,
 * the crumtrail is next saved (in binary format) in a file under the hex tree.
 * </p>
 * <h3>Reading / Lookup</h3>
 * <p>
 * Finding and returnng crumtrail for a witnessed hash is also a 2 step
 * process. First, a crumtrail for that hash is looked up and loaded
 * from the hex tree directory structure. Second, assuming it was found,
 * the loaded crumtrail is updated with the chain's latest block proof
 * before being returned.
 * </p>
 * <h4>Some Details</h4>
 * <p>
 * All files are <em>write-once</em>; furthermore, all file-writes are
 * <em>staged</em>.
 * The precise rule for concurrency is that the repo's global chain block
 * proof (hereafter <em>chain</em>) can only ever be appended. Altho this
 * class is strict about enforcing these rules, it's also a goal to make it
 * this enforcement process agnostic.
 * </p><p>
 * The "current" chain file (the global block proof) is represented by a
 * numbered file encoding its last (highest) recorded timechain block.
 * Since successive chain files include all ancestor data, lower numbered
 * chain files can be removed without loss of information. (There are
 * race condition checks to be made before deleting these.)
 * </p>
 * TODO: patch files need more validation (guard against mischievous timechain
 * servers).
 */
public class TrailRepo {


  public final static String CARGO_PROOF_EXT = ".crums";
  public final static String BLOCK_PROOF_EXT = ".chain";
  public final static String STATE_PATCH_EXT = BLOCK_PROOF_EXT + ".patch";

  private final static int BX_LEN = BLOCK_PROOF_EXT.length();
  private final static int PX_LEN = STATE_PATCH_EXT.length();

  /** Staging directory name. */
  public final static String STAGING = "staging";


  public final static String LOG_NAME = "io.crums.tc.client";


  private final static FilenameFilter BP_FILTER =
      new FilenameFilter() {
        @Override public boolean accept(File dir, String name) {
          return name.endsWith(BLOCK_PROOF_EXT);
        }
      };

  private final static FilenameFilter PATCH_FILTER =
      new FilenameFilter() {
        @Override public boolean accept(File dir, String name) {
          return name.endsWith(STATE_PATCH_EXT);
        }
      };

  protected final File dir;
  protected final File stagingDir;
  protected final HexPathTree trailTree;



  public TrailRepo(File dir) {
    this.dir = dir;
    this.stagingDir = FileUtils.ensureDir(new File(dir, STAGING));
    this.trailTree = new HexPathTree(dir, CARGO_PROOF_EXT);
  }


  /**
   * Directory this instance lives in.
   */
  public final File dir() {
    return dir;
  }


  /**
   * Finds and returns a crumtrail without lineage.
   * 
   * @param hash        32-byte hash
   * 
   * @return {@code findTrail(hash, false)}
   * 
   * @see #findTrail(ByteBuffer, boolean)
   */
  public Optional<Crumtrail> findTrail(ByteBuffer hash) {
    return findTrail(hash, false);
  }

  /**
   * Finds and returns a crumtrail for the given 32-byte hash.
   * 
   * @param hash        32-byte hash
   * @param incLineage  if {@code true} the trail's lineage from the genesis block
   *                    is included
   */
  public Optional<Crumtrail> findTrail(ByteBuffer hash, boolean incLineage) {
    if (hash.remaining() != Constants.HASH_WIDTH)
      throw new IllegalArgumentException("illegal hash length: " + hash);
    
    String hex = IntegralStrings.toHex(hash);
    File trailFile = trailTree.find(hex);
    if (trailFile == null)
      return Optional.empty();
    
    Crumtrail trail = Crumtrail.load(FileUtils.loadFileToMemory(trailFile));
    // sanity check
    if (!trail.crum().hash().equals(hash))
      throw new IllegalStateException(
          "crum hash conflicts for hex path " + trailFile);

    final long tbn = trail.blockNo();
    final var stateProof = chainState();
    if (stateProof == null || !stateProof.chainState().hasRow(tbn)) {
      System.getLogger(LOG_NAME).log(
          Level.WARNING,
          "expected block [" + tbn + "] missing for " + trail.crum().hashHex());
      return Optional.of(trail);
    }

    
    
    var blockProof =
        chainPatch(stateProof.blockNo())
        .map(patch -> stateProof.appendTail(patch))
        .orElse(stateProof)
        .forBlockNo(trail.blockNo(), incLineage)
        .orElseThrow(() -> new IllegalStateException(
            "chain block proof [" + stateProof.blockNo() +
            "] does not contain trail block [" + trail.blockNo() + "] for " +
            trail.crum()));
    
    return Optional.of(trail.setBlockProof(blockProof));
  }


  /**
   * Searches for a <em>single</em> crum trail with the given hash
   * <em>prefix</em>, and returns it, if found. That is, if empty is
   * returned, then it is either because no such entry exists,
   * or because the prefix is ambiguous.
   * 
   * @param hex           hexadecimal prefix of hash searched
   * @param incLineage    if {@code true}, then the timechain's gensis block
   *                      is included in the returned trail's block proof
   * 
   * @return  present, if exists and not {@code hex} prefix not ambiguous
   * @see #findTrailHashes(String, int)
   */
  public Optional<Crumtrail> findTrailByHex(String hex, boolean incLineage) {
    var hexList = findTrailHashes(hex, 2);
    return
        hexList.size() == 1 ?
        findTrail(
          ByteBuffer.wrap(IntegralStrings.hexToBytes(hexList.getFirst())),
          incLineage) :
        Optional.empty();
  }


  /**
   * Returns a list of hashes in the repo that start with the given hex prefix.
   *
   * @param hexPrefix   hash prefix in hexadecimal digits
   * @param limit       the maximum number of matches returned (&gt; 0)
   *
   * @return possibly empty, but no larger in size than {@code limit}
   */
  public List<String> findTrailHashes(String hexPrefix, int limit) {
    if (limit < 1)
      throw new IllegalArgumentException("limit must be positive: " + limit);
    return
        trailTree.streamStartingFrom(hexPrefix)
        .limit(limit)
        .filter(e -> e.hex.startsWith(hexPrefix))
        .map(e -> e.hex)
        .toList();
  }



  /**
   * Adds the given crumtrail to the repo. Crumtrails are assumed to be added
   * in order of creation.
   * 
   * @param trail   the new trail
   * 
   * @throws HashConflictException
   *          if the given trail block hashes conflict with those of the
   *          timechain recorded in this repo
   *          
   */
  public void add(Crumtrail trail) throws HashConflictException {
    if (trail.isCondensed())
      throw new IllegalArgumentException("condensed crumtrail " + trail);

    var repoChain = chainState();
    if (repoChain == null) {
      init(trail);
      return;
    }

    // Update the repo's block proof, first

    var trailChain = trail.blockProof();

    final long trailBn = trail.blockNo();
    final long repoBn = repoChain.blockNo();

    if (trailBn > repoBn) {

      if (!trailChain.chainState().hasRow(repoBn))
        throw new IllegalArgumentException(
            "block proof in trail does not reference block [" +
            repoBn + "], the last block in repo " + this);

      // following throws HCE on mismatched hashes
      assertBlockHash(repoChain, trailChain, repoBn);

      Path newStatePath =
          repoChain.chainState().appendTail(
              trailChain.chainState().headPath(trail.blockNo() + 1));

      var newChain = new BlockProof(repoChain.chainParams(), newStatePath);
      writeChain(newChain);

    } else { // assert trailBn <= repoBn;

      if (trailBn != repoBn && !repoChain.chainState().hasRow(trailBn))
        throw new IllegalArgumentException(
            "trail block [" + trailBn +
            "] is (behind and) not contained in repo " + this +
            "'s block proof ending at block [" + repoBn + "]");

      assertBlockHash(repoChain, trailChain, trailBn);
    }

    // any necessary update to the chain's block proof is completed

    writeTrail(trail);
    cleanUpTo(trail.blockNo());
  }



  /**
   * Attempts to patch the repo block proof to the latest state and returns the
   * result.
   *  
   * @param patch   block proof extending the current block proof
   * @return
   * @throws HashConflictException
   *          if the hash of the latest block in the repo conflicts with that of
   *          the given argument
   * @throws IllegalArgumentException
   *          if {@code patch.isCondensed()} is true
   */
  public boolean patchState(BlockProof patch) throws HashConflictException {

    if (patch.isCondensed())
      throw new IllegalArgumentException(
          "condensed patch block proof: " + patch);
    
    long currentPatchNo = patchNos().max(Long::compare).orElse(0L);
    if (patch.blockNo() <= currentPatchNo)
      return false;

    
    var chainState = chainState();
    if (chainState == null) {
      writePatch(patch);
      removePatchesLessThan(patch.blockNo());
      return true;
    }

    if (!patch.chainState().hasRowCovered(chainState.blockNo()))
      return false;

    assertBlockHash(chainState, patch, chainState.blockNo());
    writePatch(patch);
    removePatchesLessThan(patch.blockNo());
    return true;
  }


  /** Always returns true (so that it may be AND'ed), or throws HCF. */
  private boolean assertBlockHash(
      BlockProof repoChain, BlockProof trailChain, long blockNo) {
    
    if (!repoChain.chainState().getRowHash(blockNo).equals(
        trailChain.chainState().getRowHash(blockNo)))

      throw new HashConflictException("at block [" + blockNo + "]");
    return true;
  }


  private void init(Crumtrail trail) {
    final long tbn = trail.blockNo();
    BlockProof chain;
    if (tbn != trail.blockProof().blockNo()) {
      var statePath = trail.blockProof().chainState().headPath(tbn + 1);
      assert statePath.hi() == tbn;
      chain = new BlockProof(trail.chainParams(), statePath);
    } else {
      chain = trail.blockProof();
    }
    writeChain(chain);
    removePatchesLessThan(tbn + 1L, 0);
    writeTrail(trail);
  }


  public void cleanUp() {
  }

  /** @param blockNo  (exclusive) */
  private int cleanUpTo(long blockNo) {
    return
        removeChainFilesLessThan(blockNo) + 
        removePatchesLessThan(blockNo + 1);
  }


  /**
   * Controls the history buffer size.
   * 
   * @return &ge; 0; defaults to 2.
   */
  protected int historyBufferSize() {
    return 2;
  }

  protected final int removePatchesLessThan(long blockNo) {
    return removePatchesLessThan(blockNo, historyBufferSize());
  }

  private int removePatchesLessThan(long blockNo, int buffer) {
    return removeFilesLessThan(blockNo, buffer, patchNos(), this::patchFile);
  }

  protected final int removeChainFilesLessThan(long blockNo) {
    return removeChainFilesLessThan(blockNo, historyBufferSize());
  }

  private int removeChainFilesLessThan(long blockNo, int buffer) {
    return removeFilesLessThan(blockNo, buffer, chainNos(), this::chainFile);
  }


  private int removeFilesLessThan(
      long blockNo, int buffer, Stream<Long> nos, Function<Long,File> fileFunc) {

    List<Long> eligibleNos = nos.filter(n -> n < blockNo).sorted().toList();
    
    final int maxIndex = eligibleNos.size() - buffer; // exclusive
    if (maxIndex <= 0)
      return 0;
    
    return (int)
        eligibleNos.subList(0, maxIndex).stream()
        .map(fileFunc)
        .filter(File::delete)
        .count();
  }


  private boolean writeChain(BlockProof chain) {
    return writeChain(chain, chainFile(chain.blockNo()));
  }

  private boolean writePatch(BlockProof patch) {
    return writeChain(patch, patchFile(patch.blockNo()));
  }



  private boolean writeChain(BlockProof chain, File path) {
    File staged = newStagedFile(path.getName());
    FileUtils.writeNewFile(staged, chain.serialize());
    return commitStaged(staged, path);

  }



  private boolean writeTrail(Crumtrail trail) {
    var hex = trail.crum().hashHex();
    File trailFile = trailTree.suggest(hex);
    File staged = newStagedFile(trailFile.getName());
    FileUtils.writeNewFile(staged, trail.serialize());
    return commitStaged(staged, trailFile);
  }
  

 
  protected final File chainFile(long tbn) {
    return new File(dir, tbn + BLOCK_PROOF_EXT);
  }


  protected final File patchFile(long bn) {
    return new File(dir, bn + STATE_PATCH_EXT);
  }



  protected final List<Long> listChainFileNos() {
    return chainNos().sorted().toList();
  }

  

  protected final List<Long> listPatchFileNos() {
    return patchNos().sorted().toList();
  }


  private Stream<Long> chainNos() {
    return entityNos(BP_FILTER, BX_LEN);
  }

  private Stream<Long> patchNos() {
    return entityNos(PATCH_FILTER, PX_LEN);
  }

  private Stream<Long> entityNos(FilenameFilter filter, int extLen) {
    return
        Arrays.asList(dir.list(filter)).stream()
        .map(s -> s.substring(0, s.length() - extLen))
        .map(Long::parseLong);
  }


  /**
   * Returns the timechain state as of the last crum trail saved in this
   * repo or {@code null} if the repo is empty. 
   */
  public BlockProof chainState() {
    return chainNos().max(Long::compare).map(this::loadChain).orElse(null);
  }

  public Optional<BlockProof> chainPatch(long fromBlockNo) {
    return
        patchNos()
        .filter(bn -> bn > fromBlockNo)
        .max(Long::compare)
        .map(this::loadPatch);
  }


  /**
   * Returns the crum trail chain's block no. Unless assertions are turned on,
   * the chain file is not actually loaded.
   * 
   * @return the block no. of the latest (youngest) crum trail.
   */
  public long blockNo() {
    long bn = chainNos().max(Long::compare).orElse(0L);
    assert bn == 0 || chainState().blockNo() == bn;
    return bn;
  }


  /**
   * Returns the highest block no. recorded in this repo.
   */
  public long commitNo() {
    long pn = patchNos().max(Long::compare).orElse(0L);
    return Math.max(pn, blockNo());
  }



  private BlockProof loadChain(long tbn) {
    var mem = FileUtils.loadFileToMemory(chainFile(tbn));
    return BlockProof.load(mem);
  }


  private BlockProof loadPatch(long tbn) {
    var mem = FileUtils.loadFileToMemory(patchFile(tbn));
    return BlockProof.load(mem);
  }


  private File newStagedFile(String postfixName) {
    return new File(stagingDir, RandomId.RUN_INSTANCE + "_" + postfixName);
  }


  private boolean commitStaged(File staged, File target) {
    if (staged.renameTo(target))
      return true;
    
    if (!target.exists())
      throw new UncheckedIOException(
          "failed to commit (rename/move) to " + target,
          new IOException());

    return false;
  }







  @Override
  public String toString() {
    return "<" + dir.getName() + ">";
  }


}




