/*
 * Copyright 2024 Babak Farhang
 */
package io.crums.tc;


import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Objects;

import io.crums.util.api.Serial;
import io.crums.util.api.SerialFormatException;
import io.crums.util.io.buffer.BufferUtils;
import io.crums.sldg.HashConflictException;
import io.crums.sldg.Path;
import io.crums.tc.except.BlockNotFoundException;
import io.crums.tc.except.CargoConflictException;
import io.crums.tc.except.TimeChainException;

/**
 * Proof that a crum's {@linkplain Crum#witnessHash() witness hash} is
 * linked to a time chain. There are 2 versions of proofs:
 * <ol>
 * <li>A Merkle proof asserting the crum's (witness) hash in a Merkle tree,
 * coupled with a proof asserting said merkle tree's root hash, as
 * the cargo hash of a block in a time chain.</li>
 * <li>A proof asserting the crum's (witness) hash is written directly
 * in as the cargo hash of a block in a time chain.</li>
 * </ol>
 * <h2>TODO: Design peeve</h2>
 * <p>
 * The {@linkplain CargoProof} class ought to implement the logic of
 * both a merkle tree and a crum's straight hash (what this class
 * does). Punting for now, in the interest of time.
 * </p><p>
 * The cleanest way to fix this is to modify the merkle-tree library so
 * that root hash of a singleton tree is the item itelf. (I want to rewrite
 * that library wholesale, but time management will not permit.)
 * </p>
 */
public abstract class Crumtrail implements Serial {
  
  
  /**
   * Constructs a proof for when a single crum is recorded in the block.
   * The crum's {@linkplain Crum#witnessHash() witness hash} is recorded
   * directly in the cargo hash of some block in the time chain's
   * block proof.
   */
  public static LoneTrail newLoneTrail(BlockProof blockProof, Crum crum)
      throws TimeChainException {
    return new LoneTrail(blockProof, crum);
  }
  
  /**
   * Constructs a proof for when multiple crums are recorded in the block.
   * The crum's {@linkplain Crum#witnessHash() witness hash} is recorded
   * in a Merkle proof linking it to the root of a Merkle tree. The root
   * hash of the Merkle tree, in turn, is the block's cargo hash.
   */
  public static MerkleTrail newMerkleTrail(
      BlockProof blockProof, CargoProof cargoProof)
      throws TimeChainException {
    
    return new MerkleTrail(blockProof, cargoProof);
  }
  
  
  
  
  
  protected final BlockProof blockProof;

  
  private Crumtrail(BlockProof blockProof) {
    this.blockProof = Objects.requireNonNull(blockProof);
  }


  /**
   * Tests whether the instance is compressed. (Note the only component of
   * this hash proof that may be compressable is the {@linkplain
   * #blockProof() block proof}).
   */
  public final boolean isCompressed() {
    return blockProof.isCompressed();
  }


  public final boolean isCondensed() {
    return blockProof.isCondensed();
  }



  /**
   * Returns a compressed version of this instance, if not already
   * compressed; otherwise, this instance is returned.
   * 
   * @see #isCompressed()
   */
  public abstract Crumtrail compress();
  
  
  public final BlockProof blockProof() {
    return blockProof;
  }


  /** Returns an instance with the given block proof (after validation). */
  public abstract Crumtrail setBlockProof(BlockProof blockProof)
      throws HashConflictException;
  
  
  public final ChainParams chainParams() {
    return blockProof.chainParams();
  }
  
  
  /**
   * Returns the crum.
   */
  public abstract Crum crum();
  
  
  /**
   * Returns the hash observed in the block. When the block
   * records multiple crums, it's a hash of the collection
   * (root of a Merkle tree); when it records a single
   * crum, this is just the hash of that one (40-byte) crum.
   * 
   * @see #crumsInBlock()
   */
  public abstract ByteBuffer cargoHash();

  /**
   * Returns the block hash at this trail's block no.
   * 
   * @return {@code blockProof().chainState().getRowHash(blockNo())}
   * @see #blockNo()
   */
  public final ByteBuffer blockHash() {
    return blockProof.chainState().getRowHash(blockNo());
  }
  
  /**
   * Returns the block number to which the crum's witness hash is committed.
   */
  public final long blockNo() {
    return blockProof.chainParams().blockNoForUtc(crum().utc());
  }
  
  /**
   * Returns the number of crums encoded in the cargo hash.
   * 
   * @return positive
   * @see #cargoHash()
   */
  public abstract int crumsInBlock();
  
  
  /**
   * Determines if this is a {@linkplain MerkleTrail} instance.
   * If {@code true} is returned, then {@linkplain #asMerkleTrail()}
   * can safely be invoked.
   */
  public final boolean isMerkled() {
    return crumsInBlock() > 1;
  }
  
  /**
   * Returns (casts) this instance as a {@linkplain MerkleTrail}.
   * 
   * @throws ClassCastException
   *         if the instance is not {@linkplain #isMerkled() merkled}
   * @see #isMerkled()
   */
  public final MerkleTrail asMerkleTrail() throws ClassCastException {
    return (MerkleTrail) this;
  }
  

  
  
  
  protected final void verifyCargoHashInChain()
      throws BlockNotFoundException, CargoConflictException {
    final long blockNo = blockNo();
    Path chainState = blockProof.chainState();
    if (!chainState.hasRow(blockNo)) {
      throw new BlockNotFoundException(
          "block " + blockNo + " not found in state path " +
          chainState.rowNumbers())
          .setBlockNo(blockNo);
    }
    var block = chainState.getRowByNumber(blockNo);
    if (!cargoHash().equals(block.inputHash()))
      throw new CargoConflictException(
          "cargo hash in block " + blockNo + " must match " +
          (crumsInBlock() == 1 ? " crum witness" : "merkle root") +
          " hash; crum=" + crum())
          .setBlockNo(blockNo);
  }




  
  
  
  
  
  
  /**
   * A crum trail using the root hash of a Merkle proof as the block's
   * cargo hash.
   */
  public final static class MerkleTrail extends Crumtrail {
    
    protected final CargoProof cargoProof;

    public MerkleTrail(BlockProof blockProof, CargoProof cargoProof) {
      super(blockProof);
      this.cargoProof = Objects.requireNonNull(cargoProof);
      verifyCargoHashInChain();
    }



    @Override
    public MerkleTrail setBlockProof(BlockProof newBlockProof)
      throws HashConflictException {
      
      return new MerkleTrail(newBlockProof, cargoProof);
    }


    private MerkleTrail(BlockProof blockProof, CargoProof cargoProof, boolean trustMe) {
      super(blockProof);
      this.cargoProof = cargoProof;
    }

    
    public CargoProof cargoProof() {
      return cargoProof;
    }

    @Override
    public final Crum crum() {
      return cargoProof.crum();
    }

    @Override
    public final ByteBuffer cargoHash() {
      return ByteBuffer.wrap(cargoProof.rootHash()).asReadOnlyBuffer();
    }

    @Override
    public final int crumsInBlock() {
      return cargoProof.leafCount();
    }

    @Override
    public int serialSize() {
      return blockProof.serialSize() + cargoProof.serialSize();
    }

    @Override
    public ByteBuffer writeTo(ByteBuffer out) throws BufferOverflowException {
      blockProof.writeTo(out);
      cargoProof.writeTo(out);
      return out;
    }


    @Override
    public MerkleTrail compress() {
      return
          isCompressed() ?
              this :
              new MerkleTrail(blockProof.compress(), cargoProof, true);
    }
    
    
    
  }
  
  
  /**
   * Proof for a lone crum in a block. The crum's witness
   * hash <em>is</em> the block's cargo hash.
   */
  public final static class LoneTrail extends Crumtrail {
    
    private final Crum crum;

    public LoneTrail(BlockProof blockProof, Crum crum) {
      super(blockProof);
      this.crum = Objects.requireNonNull(crum);
      verifyCargoHashInChain();
    }

    @Override
    public LoneTrail setBlockProof(BlockProof newBlockProof)
      throws HashConflictException {
      
      return new LoneTrail(newBlockProof, crum);
    }


    private LoneTrail(BlockProof blockProof, Crum crum, boolean trustMe) {
      super(blockProof);
      this.crum = crum;
    }

    @Override
    public Crum crum() {
      return crum;
    }

    @Override
    public ByteBuffer cargoHash() {
      return ByteBuffer.wrap(crum.witnessHash()).asReadOnlyBuffer();
    }

    @Override
    public int crumsInBlock() {
      return 1;
    }

    @Override
    public int serialSize() {
      return 4 + Crum.DATA_SIZE + blockProof.serialSize();
    }

    @Override
    public ByteBuffer writeTo(ByteBuffer out) throws BufferOverflowException {
      blockProof.writeTo(out);
      out.putInt(1);
      crum.writeTo(out);
      return out;
    }


    @Override
    public LoneTrail compress() {
      return
          isCompressed() ?
              this :
              new LoneTrail(blockProof.compress(), crum, true);
    }
    
    
  }
  
  
  public static Crumtrail load(ByteBuffer in) throws SerialFormatException {
    try {
      var blockProof = BlockProof.load(in);
      int crums = in.getInt();
      if (crums < 1)
        throw new SerialFormatException("crum count: " + crums);
      
      if (crums == 1) {
        var cbuf = BufferUtils.slice(in, Crum.DATA_SIZE);
        var crum = new Crum(cbuf);
        return new LoneTrail(blockProof, crum);
      
      } else {
        var cargoProof = CargoProof.load(in, crums);
        return new MerkleTrail(blockProof, cargoProof);
      }
    } catch (SerialFormatException sfx) {
      throw sfx;
    } catch (Exception x) {
      throw new SerialFormatException(x);
    }
  }
  

}

