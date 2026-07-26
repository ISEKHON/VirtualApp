package com.lody.virtual.helper.compat;

import android.content.pm.Signature;

import com.lody.virtual.helper.utils.VLog;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java reader for the certificates stored in an APK Signing Block
 * (APK Signature Scheme v2 / v3).
 *
 * <p>On Android 14+ the framework {@code PackageParser.collectCertificates()}
 * path frequently throws on vendor ROMs. When that happens VirtualApp used to
 * fall back to a hard-coded fake signature, which breaks every app that verifies
 * its own signing certificate (Google Play Services, Google Sign-In, banking
 * apps, ...). This helper recovers the app's <b>real</b> signer certificate
 * directly from the APK without touching any hidden framework API, so it can be
 * used as a fallback tier before the fake signature.
 *
 * <p>The implementation follows the documented APK Signing Block layout:
 * <pre>
 *   ... ZIP entries ...
 *   APK Signing Block:
 *     uint64  size_of_block (excluding this field)
 *     repeated ID-value pairs: uint64 length | uint32 id | value[length-4]
 *     uint64  size_of_block (repeated)
 *     byte[16] magic "APK Sig Block 42"
 *   ZIP Central Directory
 *   ZIP End of Central Directory
 * </pre>
 */
public final class ApkSignatureExtractor {

    private static final String TAG = "ApkSigExtractor";

    // ID-value block ids.
    private static final int APK_SIGNATURE_SCHEME_V2_BLOCK_ID = 0x7109871a;
    private static final int APK_SIGNATURE_SCHEME_V3_BLOCK_ID = 0xf05368c0;
    // v3.1 (Android 13+) uses the same signer layout as v3.
    private static final int APK_SIGNATURE_SCHEME_V31_BLOCK_ID = 0x1b93ad61;

    private static final long APK_SIG_BLOCK_MAGIC_HI = 0x3234206b636f6c42L; // "Block 42"
    private static final long APK_SIG_BLOCK_MAGIC_LO = 0x20676953204b5041L; // "APK Sig "
    private static final int APK_SIG_BLOCK_MIN_SIZE = 32;

    private static final int ZIP_EOCD_REC_MIN_SIZE = 22;
    private static final int ZIP_EOCD_REC_SIG = 0x06054b50;
    private static final int ZIP_EOCD_CENTRAL_DIR_OFFSET_FIELD_OFFSET = 16;
    private static final int UINT16_MAX_VALUE = 0xffff;

    private ApkSignatureExtractor() {
    }

    /**
     * Extract the signer certificates of an APK from its v2/v3 signing block.
     *
     * @return a non-empty {@link Signature} array on success, or {@code null}
     * if the APK is v1-only, unsigned, or could not be parsed.
     */
    public static Signature[] extract(File apkFile) {
        if (apkFile == null || !apkFile.exists()) {
            return null;
        }
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(apkFile, "r");
            long fileSize = raf.length();
            if (fileSize < ZIP_EOCD_REC_MIN_SIZE) {
                return null;
            }

            long centralDirOffset = findCentralDirOffset(raf, fileSize);
            if (centralDirOffset <= 0) {
                return null;
            }

            ByteBuffer sigBlock = findApkSigningBlock(raf, centralDirOffset);
            if (sigBlock == null) {
                return null;
            }

            // Prefer the newest scheme available.
            Signature[] sigs = findSignerCerts(sigBlock, APK_SIGNATURE_SCHEME_V31_BLOCK_ID);
            if (sigs == null) {
                sigs = findSignerCerts(sigBlock, APK_SIGNATURE_SCHEME_V3_BLOCK_ID);
            }
            if (sigs == null) {
                sigs = findSignerCerts(sigBlock, APK_SIGNATURE_SCHEME_V2_BLOCK_ID);
            }
            if (sigs != null && sigs.length > 0) {
                VLog.i(TAG, "Recovered real signature from APK signing block: " + apkFile.getName());
                return sigs;
            }
            return null;
        } catch (Throwable e) {
            VLog.w(TAG, "extract failed for " + apkFile, e);
            return null;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static long findCentralDirOffset(RandomAccessFile raf, long fileSize) throws Exception {
        // Search the End Of Central Directory record from the end of the file.
        long maxCommentLength = Math.min(fileSize - ZIP_EOCD_REC_MIN_SIZE, UINT16_MAX_VALUE);
        long scanStart = fileSize - ZIP_EOCD_REC_MIN_SIZE - maxCommentLength;
        int scanLen = (int) (fileSize - scanStart);
        byte[] buf = new byte[scanLen];
        raf.seek(scanStart);
        raf.readFully(buf);
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = scanLen - ZIP_EOCD_REC_MIN_SIZE; i >= 0; i--) {
            if (bb.getInt(i) == ZIP_EOCD_REC_SIG) {
                long cdOffset = bb.getInt(i + ZIP_EOCD_CENTRAL_DIR_OFFSET_FIELD_OFFSET) & 0xffffffffL;
                if (cdOffset < fileSize) {
                    return cdOffset;
                }
            }
        }
        return -1;
    }

    private static ByteBuffer findApkSigningBlock(RandomAccessFile raf, long centralDirOffset) throws Exception {
        if (centralDirOffset < APK_SIG_BLOCK_MIN_SIZE) {
            return null;
        }
        // Read the footer (size + magic) that sits immediately before the central directory.
        byte[] footer = new byte[24];
        raf.seek(centralDirOffset - footer.length);
        raf.readFully(footer);
        ByteBuffer fb = ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN);
        if (fb.getLong(8) != APK_SIG_BLOCK_MAGIC_LO || fb.getLong(16) != APK_SIG_BLOCK_MAGIC_HI) {
            return null; // No APK Signing Block (v1-only or unsigned).
        }
        long blockSizeInFooter = fb.getLong(0);
        if (blockSizeInFooter < footer.length || blockSizeInFooter > Integer.MAX_VALUE - 8) {
            return null;
        }
        int totalSize = (int) (blockSizeInFooter + 8);
        long blockStart = centralDirOffset - totalSize;
        if (blockStart < 0) {
            return null;
        }
        byte[] block = new byte[totalSize];
        raf.seek(blockStart);
        raf.readFully(block);
        ByteBuffer bb = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);
        long blockSizeInHeader = bb.getLong(0);
        if (blockSizeInHeader != blockSizeInFooter) {
            return null;
        }
        // Return the ID-value pairs region only (skip the leading size field, trailing size+magic).
        bb.position(8);
        bb.limit(totalSize - 24);
        return bb.slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    private static Signature[] findSignerCerts(ByteBuffer pairs, int wantedId) throws Exception {
        pairs.position(0);
        while (pairs.remaining() >= 12) {
            long len = pairs.getLong();
            if (len < 4 || len > pairs.remaining()) {
                break;
            }
            int id = pairs.getInt();
            int valueLen = (int) (len - 4);
            int nextPos = pairs.position() + valueLen;
            if (id == wantedId) {
                ByteBuffer value = slice(pairs, pairs.position(), valueLen);
                Signature[] result = parseSigners(value);
                if (result != null) {
                    return result;
                }
            }
            pairs.position(nextPos);
        }
        return null;
    }

    /** value = length-prefixed sequence of signers. */
    private static Signature[] parseSigners(ByteBuffer value) throws Exception {
        ByteBuffer signers = getLengthPrefixed(value);
        if (signers == null) {
            return null;
        }
        List<Signature> out = new ArrayList<>();
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        while (signers.remaining() >= 4) {
            ByteBuffer signer = getLengthPrefixed(signers);
            if (signer == null) {
                break;
            }
            ByteBuffer signedData = getLengthPrefixed(signer);
            if (signedData == null) {
                continue;
            }
            // signedData = digests (len-prefixed) | certificates (len-prefixed) | ...
            getLengthPrefixed(signedData); // skip digests
            ByteBuffer certificates = getLengthPrefixed(signedData);
            if (certificates == null) {
                continue;
            }
            while (certificates.remaining() >= 4) {
                ByteBuffer cert = getLengthPrefixed(certificates);
                if (cert == null) {
                    break;
                }
                byte[] encoded = new byte[cert.remaining()];
                cert.get(encoded);
                try {
                    X509Certificate x509 = (X509Certificate) cf.generateCertificate(
                            new java.io.ByteArrayInputStream(encoded));
                    out.add(new Signature(x509.getEncoded()));
                } catch (Throwable ignored) {
                }
                // Only the first (signing) certificate of each signer is the app's identity.
                break;
            }
        }
        return out.isEmpty() ? null : out.toArray(new Signature[0]);
    }

    private static ByteBuffer getLengthPrefixed(ByteBuffer buf) {
        if (buf.remaining() < 4) {
            return null;
        }
        int len = buf.getInt();
        if (len < 0 || len > buf.remaining()) {
            return null;
        }
        ByteBuffer result = slice(buf, buf.position(), len);
        buf.position(buf.position() + len);
        return result;
    }

    private static ByteBuffer slice(ByteBuffer buf, int position, int length) {
        int oldLimit = buf.limit();
        int oldPos = buf.position();
        buf.position(position);
        buf.limit(position + length);
        ByteBuffer result = buf.slice().order(ByteOrder.LITTLE_ENDIAN);
        buf.limit(oldLimit);
        buf.position(oldPos);
        return result;
    }
}
