/*
 * Copyright 2024 Babak Farhang
 */
package io.crums.tc;


import static io.crums.tc.Constants.DIGEST;
import static io.crums.tc.Constants.HASH_WIDTH;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.Arrays;
import java.util.Objects;

import io.crums.util.api.Serial;
import io.crums.util.io.channels.ChannelUtils;
import io.crums.tc.except.MerkleProofException;
import io.crums.util.mrkl.Proof;
import io.crums.util.mrkl.Tree;

/**
 * A Merkle proof linking a crum to a block's cargo hash.
 * <em>Instances are cryptographically validated on construction</em>.
 * 
 * <h2>Historical Note</h2>
 * <p>
 * This used to be called {@code CrumTrail}. In TC-2 we're
 * augmenting the (conceptual) crum trail with another proof
 * asserting which block in the chain the root hash of the Merkle tree
 * belongs in.
 * </p>
 * 
 * <h2>A Design Peeve</h2>
 * <p>
 * Not sure whether the Merkle root hash itself should be included as
 * part of a Merkle proof. After all, it's redundant. On the other hand,
 * Merkle roots are usually are already recorded <em>somewhere</em>, and
 * since they're not cheap to compute (even in a proof), maybe it's worth
 * memo-ising the result (I tend to think not).
 * </p>
 * 
 * @see #crum()
 * @see Proof
 * @see Crumtrail
 */
public class CargoProof extends Proof implements Serial {
  

  /**
   * Loads and returns an instance from the given stream. Does not read beyond
   * the crumtrail's data (which is of variable length).
   * 
   * @param in the steam to load from
   * 
   * @see #writeTo(ByteBuffer)
   */
  public static CargoProof load(ReadableByteChannel in) throws IOException {
    return load(in, ByteBuffer.allocate(8));
  }

  /**
   * Loads and returns an instance from the given stream. Does not read beyond
   * the crumtrail's data (which is of variable length).
   * 
   * @param in the steam to load from
   * @param work work buffer with at least 8 bytes capacity (2k recommended)
   * 
   * @see #writeTo(ByteBuffer)
   */
  public static CargoProof load(ReadableByteChannel in, ByteBuffer work) throws IOException {
    if (Objects.requireNonNull(work, "null work").capacity() < 8)
      throw new IllegalArgumentException("capacity < 8: " + work);
    
    work.clear().limit(8);
    ChannelUtils.readRemaining(in, work).flip();
    final int leafCount = work.getInt();
    final int leafIndex = work.getInt();
    
    final int chainLength = chainLength(leafCount, leafIndex);
    
    // prepare buffer to read chain
    {
      int capRequired = chainLength * HASH_WIDTH + Crum.DATA_SIZE;
      if (work.capacity() < capRequired)
        work = ByteBuffer.allocate(capRequired);
      else
        work.clear();
      
      work.limit(capRequired);
    }
    
    
    ChannelUtils.readRemaining(in, work).flip();
    
    byte[][] chain = new byte[chainLength][];
    for (int index = 0; index < chainLength; ++index) {
      byte[] hash = new byte[HASH_WIDTH];
      work.get(hash);
      chain[index] = hash;
    }
    
    ByteBuffer cbuf = ByteBuffer.allocate(Crum.DATA_SIZE);
    cbuf.put(work);
    // below unnecessary because the code after it validates
//    assert !cbuf.hasRemaining(); 
    
    Crum crum = new Crum(cbuf.flip());
    
    return new CargoProof(leafCount, leafIndex, chain, crum);
  }
  
  
  public static CargoProof load(ByteBuffer in) {
    return load(in, in.getInt());
  }
  
  public static CargoProof load(ByteBuffer in, int leafCount) throws BufferUnderflowException {

    final int leafIndex = in.getInt();

    final int chainLength = chainLength(leafCount, leafIndex);

    byte[][] chain = new byte[chainLength][];
    for (int index = 0; index < chainLength; ++index) {
      byte[] hash = new byte[HASH_WIDTH];
      in.get(hash);
      chain[index] = hash;
    }
    
    
    ByteBuffer cbuf = ByteBuffer.allocate(Crum.DATA_SIZE);

    if (in.remaining() > Crum.DATA_SIZE) {
      int savedLimit = in.limit();
      in.limit(in.position() + Crum.DATA_SIZE);
      
      cbuf.put(in);
      int pos = in.limit();
      in.limit(savedLimit).position(pos);
      
    } else
      cbuf.put(in);
    
    // below unnecessary because the code after it validates
//    assert !cbuf.hasRemaining(); 
    
    Crum crum = new Crum(cbuf.flip());
    
    return new CargoProof(leafCount, leafIndex, chain, crum);
  }
  
  
  
  
  private final Crum crum;
  
  
  /**
   * Constructs a verified instance.
   * 
   * @param crum        the crum used to generate the
   *                    leaf hash of the merkle tree at
   *                    the given {@code leafIndex}
   *                    
   * @see Proof#Proof(Tree, int)
   */
  public CargoProof(Tree tree, int leafIndex, Crum crum) {
    super(tree, leafIndex);
    this.crum = Objects.requireNonNull(crum, "null crum");
    verifyProof();
  }

  /**
   * Constructs a verified instance.
   * 
   * @param crum        the crum used to generate the
   *                    leaf hash of the merkle tree at
   *                    the given {@code leafIndex}
   * 
   * @see Proof#Proof(String, int, int, byte[][])
   */
  public CargoProof(int leafCount, int leafIndex, byte[][] chain, Crum crum) {
    super(Constants.HASH_ALGO, leafCount, leafIndex, chain);
    this.crum = Objects.requireNonNull(crum, "null crum");
    verifyProof();
  }

  /**
   * Constructs a verified instance.
   * 
   * @param proof       plain merkle proof
   * @param crum        the crum used to generate the
   *                    leaf hash of the merkle tree at
   *                    the given {@code leafIndex}
   * 
   * @see Proof#Proof(String, int, int, byte[][])
   */
  public CargoProof(Proof proof, Crum crum) {
    super(proof);
    this.crum = Objects.requireNonNull(crum, "null crum");
    verifyProof();
  }
  
  
  /**
   * Copy constructor does not need to validate.
   */
  protected CargoProof(CargoProof cargoProof) {
    super(cargoProof);
    this.crum = cargoProof.crum;
  }
  
  
  

  /**
   * Returns the crum in this proof. The first element of the {@linkplain #hashChain()} is the
   * <em>hash</em> of the returned crum. 
   * 
   * @return the crum this proof is about
   */
  public final Crum crum() {
    return crum;
  }
  
  
  /**
   * Returns the SHA-256 hash of the {@linkplain #crum() crum}. This is just a
   * synonym for {@linkplain #item()}.
   */
  public final ByteBuffer hashedCrum() {
    return ByteBuffer.wrap(item());
  }
  
  /**
   * Verifies this proof. This verifies both the Merkle
   * proof and that the leaf hash in the Merkle proof indeed
   * matches the hash of the crum.
   */
  private void verifyProof() {
    var digest = DIGEST.newDigest();
    if (!verify(digest))
      throw new MerkleProofException(
          "merkle proof fails to verification");
    byte[] crumHash = crum.witnessHash(digest);
    if (!Arrays.equals(crumHash, item()))
      throw new MerkleProofException(
          "crum hash does not match leaf hash in merkle proof");
  }
  
  
  
  @Override
  public ByteBuffer writeTo(ByteBuffer buffer) throws BufferOverflowException {
    buffer.putInt(leafCount()).putInt(leafIndex());
    for (byte[] link : hashChain())
      buffer.put(link);
    
    return buffer.put(crum.serialForm());
  }
  

  @Override
  public int serialSize() {
    return Crum.DATA_SIZE + 8 + HASH_WIDTH * chainLength(leafCount(), leafIndex());
  }
  
  
  
  

  
  
  
  
}














