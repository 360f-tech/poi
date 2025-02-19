/* ====================================================================
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
==================================================================== */

package org.apache.poi.poifs.crypt.standard;

import static org.apache.poi.poifs.crypt.DataSpaceMapUtils.createEncryptionEntry;
import static org.apache.poi.poifs.crypt.standard.StandardDecryptor.generateSecretKey;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.poifs.crypt.CryptoFunctions;
import org.apache.poi.poifs.crypt.DataSpaceMapUtils;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionVerifier;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.POIFSWriterEvent;
import org.apache.poi.poifs.filesystem.POIFSWriterListener;
import org.apache.poi.util.IOUtils;
import org.apache.poi.util.LittleEndianByteArrayOutputStream;
import org.apache.poi.util.LittleEndianConsts;
import org.apache.poi.util.LittleEndianOutputStream;
import org.apache.poi.util.RandomSingleton;
import org.apache.poi.util.TempFile;

public class StandardEncryptor extends Encryptor {
    private static final Logger LOG = LogManager.getLogger(StandardEncryptor.class);

    protected StandardEncryptor() {}

    protected StandardEncryptor(StandardEncryptor other) {
        super(other);
    }

    @Override
    public void confirmPassword(String password) {
        // see [MS-OFFCRYPTO] - 2.3.3 EncryptionVerifier
        SecureRandom r = RandomSingleton.getInstance();
        byte[] salt = new byte[16], verifier = new byte[16];

        // using a java.security.SecureRandom (and avoid allocating a new SecureRandom for each random number needed).
        r.nextBytes(salt);
        r.nextBytes(verifier);

        confirmPassword(password, null, null, salt, verifier, null);
    }


    /**
     * Fills the fields of verifier and header with the calculated hashes based
     * on the password and a random salt
     *
     * see [MS-OFFCRYPTO] - 2.3.4.7 ECMA-376 Document Encryption Key Generation
     */
    @Override
    public void confirmPassword(String password, byte[] keySpec, byte[] keySalt, byte[] verifier, byte[] verifierSalt, byte[] integritySalt) {
        StandardEncryptionVerifier ver = (StandardEncryptionVerifier)getEncryptionInfo().getVerifier();

        ver.setSalt(verifierSalt);
        SecretKey secretKey = generateSecretKey(password, ver, getKeySizeInBytes());
        setSecretKey(secretKey);
        Cipher cipher = getCipher(secretKey, null);

        try {
            byte[] encryptedVerifier = cipher.doFinal(verifier);
            MessageDigest hashAlgo = CryptoFunctions.getMessageDigest(ver.getHashAlgorithm());
            byte[] calcVerifierHash = hashAlgo.digest(verifier);

            // 2.3.3 EncryptionVerifier ...
            // An array of bytes that contains the encrypted form of the
            // hash of the randomly generated Verifier value. The length of the array MUST be the size of
            // the encryption block size multiplied by the number of blocks needed to encrypt the hash of the
            // Verifier. If the encryption algorithm is RC4, the length MUST be 20 bytes. If the encryption
            // algorithm is AES, the length MUST be 32 bytes. After decrypting the EncryptedVerifierHash
            // field, only the first VerifierHashSize bytes MUST be used.
            int encVerHashSize = ver.getCipherAlgorithm().encryptedVerifierHashLength;
            byte[] encryptedVerifierHash = cipher.doFinal(Arrays.copyOf(calcVerifierHash, encVerHashSize));

            ver.setEncryptedVerifier(encryptedVerifier);
            ver.setEncryptedVerifierHash(encryptedVerifierHash);
        } catch (GeneralSecurityException e) {
            throw new EncryptedDocumentException("Password confirmation failed", e);
        }

    }

    private Cipher getCipher(SecretKey key, String padding) {
        EncryptionVerifier ver = getEncryptionInfo().getVerifier();
        return CryptoFunctions.getCipher(key, ver.getCipherAlgorithm(), ver.getChainingMode(), null, Cipher.ENCRYPT_MODE, padding);
    }

    @Override
    public OutputStream getDataStream(final DirectoryNode dir)
    throws IOException, GeneralSecurityException {
        createEncryptionInfoEntry(dir);
        DataSpaceMapUtils.addDefaultDataSpace(dir);
        return new StandardCipherOutputStream(dir);
    }

    protected class StandardCipherOutputStream extends FilterOutputStream implements POIFSWriterListener {
        protected long countBytes;
        protected final File fileOut;
        protected final DirectoryNode dir;

        @SuppressWarnings({"resource", "squid:S2095"})
        private StandardCipherOutputStream(DirectoryNode dir, File fileOut) throws IOException {
            // although not documented, we need the same padding as with agile encryption
            // and instead of calculating the missing bytes for the block size ourselves
            // we leave it up to the CipherOutputStream, which generates/saves them on close()
            // ... we can't use "NoPadding" here
            //
            // see also [MS-OFFCRYPT] - 2.3.4.15
            // The final data block MUST be padded to the next integral multiple of the
            // KeyData.blockSize value. Any padding bytes can be used. Note that the StreamSize
            // field of the EncryptedPackage field specifies the number of bytes of
            // unencrypted data as specified in section 2.3.4.4.
            super(
                new CipherOutputStream(new FileOutputStream(fileOut), getCipher(getSecretKey(), "PKCS5Padding"))
            );
            this.fileOut = fileOut;
            this.dir = dir;
        }

        protected StandardCipherOutputStream(DirectoryNode dir) throws IOException {
            this(dir, TempFile.createTempFile("encrypted_package", "crypt"));
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            countBytes += len;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            countBytes++;
        }

        @Override
        public void close() throws IOException {
            // the CipherOutputStream adds the padding bytes on close()
            super.close();
            writeToPOIFS();
        }

        void writeToPOIFS() throws IOException {
            int oleStreamSize = (int)(fileOut.length()+LittleEndianConsts.LONG_SIZE);
            dir.createDocument(DEFAULT_POIFS_ENTRY, oleStreamSize, this);
            // TODO: any properties???
        }

        @Override
        public void processPOIFSWriterEvent(POIFSWriterEvent event) {
            try {
                LittleEndianOutputStream leos = new LittleEndianOutputStream(event.getStream());

                // StreamSize (8 bytes): An unsigned integer that specifies the number of bytes used by data
                // encrypted within the EncryptedData field, not including the size of the StreamSize field.
                // Note that the actual size of the \EncryptedPackage stream (1) can be larger than this
                // value, depending on the block size of the chosen encryption algorithm
                leos.writeLong(countBytes);

                try (FileInputStream fis = new FileInputStream(fileOut)) {
                    IOUtils.copy(fis, leos);
                }
                if (!fileOut.delete()) {
                    LOG.atError().log("Can't delete temporary encryption file: {}", fileOut);
                }

                leos.close();
            } catch (IOException e) {
                throw new EncryptedDocumentException(e);
            }
        }
    }

    protected int getKeySizeInBytes() {
        return getEncryptionInfo().getHeader().getKeySize()/8;
    }

    protected void createEncryptionInfoEntry(DirectoryNode dir) throws IOException {
        final EncryptionInfo info = getEncryptionInfo();
        final StandardEncryptionHeader header = (StandardEncryptionHeader)info.getHeader();
        final StandardEncryptionVerifier verifier = (StandardEncryptionVerifier)info.getVerifier();

        EncryptionRecord er = new EncryptionRecord(){
            @Override
            public void write(LittleEndianByteArrayOutputStream bos) {
                bos.writeShort(info.getVersionMajor());
                bos.writeShort(info.getVersionMinor());
                bos.writeInt(info.getEncryptionFlags());
                header.write(bos);
                verifier.write(bos);
            }
        };

        createEncryptionEntry(dir, "EncryptionInfo", er);

        // TODO: any properties???
    }

    @Override
    public StandardEncryptor copy() {
        return new StandardEncryptor(this);
    }
}
