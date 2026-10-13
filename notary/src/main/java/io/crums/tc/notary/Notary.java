/*
 * Copyright 2024 Babak Farhang
 */
package io.crums.tc.notary;


import static io.crums.tc.notary.NotaryConstants.CARGO_DIR;
import static io.crums.tc.notary.NotaryConstants.CHAIN;
import static io.crums.tc.notary.NotaryConstants.NOTARY_PROPS;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.util.Objects;

import io.crums.util.io.FileUtils;
import io.crums.tc.BlockProof;
import io.crums.tc.ChainParams;
import io.crums.tc.Crum;
import io.crums.tc.NotaryPolicy;
import io.crums.tc.NotaryService;
import io.crums.tc.Receipt;
import io.crums.tc.TimeBinner;
import io.crums.tc.TimeChain;
import io.crums.util.TaskStack;

/**
 * Main abstraction for witnessing hashes and vending out
 * receipts.
 */
public class Notary implements NotaryService, Channel {
  
  
  /**
   * Incepts a new notary in the given directory and returns it.
   * 
   * 
   * @param dir               directory notary will live in (created on demand)
   * @param binner            determimnes the time interval each block spans; 
   *                          &ge; {@linkplain TimeBinner#QUARTER_SEC}
   * @param startUtc          inception UTC (truncated to time bin boundary)
   * @param blocksRetained    the number of rolling cargo blocks
   *                          (&ge; 3, but allow more.. way more);
   *                          see {@link NotaryPolicy#blocksRetained()}
   */
  public static Notary incept(
      File dir,
      TimeBinner binner,
      long startUtc,
      int blocksRetained)
  
          throws IOException {
    
    
    var settings = new NotarySettings(
        ChainParams.forStartUtc(binner, startUtc),
        blocksRetained);
    
    return incept(dir, settings);
    
  }
  
  

  
  /**
   * Incepts a new notary in the given directory and returns it. Creates
   * a time-chain file and a "CARGO" subdirectory.
   * 
   * @param dir       path to directory notary will live in
   * @param settings  (includes chain params)
   */
  public static Notary incept(File dir, NotarySettings settings)
      throws IOException {

    return incept(dir, settings, NotaryLog.SYS);
  }

  
  
  /**
   * Incepts a new notary in the given directory and returns it. Creates
   * a time-chain file and a "CARGO" subdirectory.
   * 
   * @param dir       path to directory notary will live in
   * @param settings  (includes chain params)
   * @param log       not {@code null}
   */
  public static Notary incept(File dir, NotarySettings settings, NotaryLog log)
      throws IOException {
    
    FileUtils.ensureDir(dir);
    
    final File chainFile = new File(dir, CHAIN);
    final File settingsFile = new File(dir, NOTARY_PROPS);
    
    try (var closeOnFail = new TaskStack()) {
      
      // write the settings file
      {
        var writer = new FileOutputStream(settingsFile);
        closeOnFail.pushClose(writer);
        
        var comment = "Notary settings";
        settings.toProperties().store(writer, comment);
        writer.close();
      }
      
      TimeChain chain =
          TimeChain.inceptNewChain(
              chainFile,
              settings.chainParams().timeBinner(),
              settings.chainParams().inceptionUtc());
      
      closeOnFail.pushClose(chain);
      
      var notary = new Notary(chain, settings, log, null);
      
      closeOnFail.clear();
      
      return notary;
    }
  }
  
  
  
  
  /**
   * Loads and returns an instance from the given directory.
   */
  public static Notary load(File dir) throws IOException {
    
    return load(dir, NotaryLog.SYS);
    
  }
  
  
  /**
   * Loads and returns an instance from the given directory.
   * 
   * @param log       not {@code null}
   */
  public static Notary load(File dir, NotaryLog log) throws IOException {
    
    Objects.requireNonNull(log);
    final File chainFile = new File(dir, CHAIN);
    final File settingsFile = new File(dir, NOTARY_PROPS);
    
    
    
    try (var closeOnFail = new TaskStack()) {
      
      var chain = TimeChain.load(chainFile);
      closeOnFail.pushClose(chain);
      
      var settings = NotarySettings.load(settingsFile, chain.params());
      
      var notary = new Notary(chain, settings, log, null);
      closeOnFail.clear();
      
      return notary;
    }
    
  }
  
  
  
  
  
  
  
  
  protected final CargoChain cargoChain;
  
  
  
  /**
   * {@code this(chain, settings, NotaryLog.SYS, null)}.
   * @see #Notary(TimeChain, NotarySettings, NotaryLog, File)
   */
  protected Notary(
      TimeChain chain, NotarySettings settings) {
    this(chain, settings, NotaryLog.SYS, null);
  }
  
  
  /**
   * Full-parameter, main constructor.
   * 
   * @param chain         the time chain
   * @param settings      its chain-params must match that of {@code chain}
   * @param log           not {@code null}
   * @param cargoChainDir if {@code null} then the default diretory named 
   *                      {@linkplain NotaryConstants#CARGO_DIR CARGO} is
   *                      chosen
   */
  protected Notary(
      TimeChain chain, NotarySettings settings,
      NotaryLog log,
      File cargoChainDir) {
    
    cargoChainDir = cargoChainDir(cargoChainDir, chain);
    var ccArgs =
        new CargoChain.InitArgs(chain, settings, cargoChainDir, log);
    
    this.cargoChain = initCargoChain(ccArgs);
  }
  
  
  
  /** Copy / promotion constructor. */
  protected Notary(Notary copy) {
    this.cargoChain = copy.cargoChain;
  }
  
  
  
  protected CargoChain initCargoChain(CargoChain.InitArgs ccArgs) {
    return new CargoChain(ccArgs);
  }
    
  
  
  private File cargoChainDir(File cargoChainDir, TimeChain chain) {
    if (cargoChainDir != null)
      return cargoChainDir;
    
    return
        new File(
            chain.file().getParentFile(),
            CARGO_DIR);
  }
  
  
  
  
  
  
  
  
  
  
  @Override
  public boolean isOpen() {
    return cargoChain.isOpen();
  }


  @Override
  public void close() {
    cargoChain.close();
  }
  
  
  
  
  /**
   * Returns the number of blocks committed to the time chain.
   * AKA, commit-no.
   */
  public long blockCount() {
    return cargoChain.timechain().size();
  }
  
  
  public ChainParams chainParams() {
    return cargoChain.timechain().params();
  }
  
  
  
  public NotarySettings settings() {
    return cargoChain.settings();
  }


  @Override
  public NotaryPolicy policy() {
    return settings();
  }


  /**
   * Witnesses the given {@code hash} and returns the
   * receipt.
   * <p>
   * First the last {@linkplain NotaryPolicy#blocksSearched() blocks-searched}
   * number of logical blocks are searched for the hash,
   * in order of descending block no. If found, a receipt is generated from
   * an existing crum and returned.
   * </p><p>
   * Otherwise, a fresh crum is stored in the block no. indicated by the
   * crum's utc and returned packaged as a {@code Receipt}.
   * </p>
   */
  @Override
  public Receipt witness(ByteBuffer hash, long fromBlockNo) {
    return cargoChain.findReceipt(hash, fromBlockNo).orElseGet(
        () -> cargoChain.addCrum(new FreshCrum(hash)));
  }
  
  
  /**
   * Returns an updated receipt for the given {@code crum}.
   * If the crum is not found in its approproriate block
   * (i.e. the crum was made up and not the return value from
   * {@link #witness(ByteBuffer)}), or if more than
   * {@linkplain NotaryPolicy#blocksRetained()} blocks have
   * since elapsed), then {@code witness(crum.hash())} is returned.
   */
  @Override
  public Receipt update(Crum crum, long fromBlockNo) {
    return cargoChain.findCrumReceipt(crum, fromBlockNo).orElseGet(
        () -> witness(crum.hash(), fromBlockNo));
  }



  @Override
  public BlockProof stateProof(boolean hi, Long... blockNos) {
    return cargoChain.timechain().stateProof(hi, blockNos);
  }
  

}







