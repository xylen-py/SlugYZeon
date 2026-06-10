package com.slugyzeon.plugin.deezer;

import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import org.apache.http.HttpResponse;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;

public class DeezerPersistentHttpStream extends PersistentHttpStream {

    private static final int BLOCK_SIZE = 2048;
    private static final byte[] IV = new byte[]{0, 1, 2, 3, 4, 5, 6, 7};

    private final byte[] keyMaterial;

    public DeezerPersistentHttpStream(HttpInterface httpInterface, URI contentUrl, Long contentLength, byte[] keyMaterial) {
        super(httpInterface, contentUrl, contentLength);
        this.keyMaterial = keyMaterial;
    }

    @Override
    public InputStream createContentInputStream(HttpResponse response) throws IOException {
        return new DecryptingInputStream(response.getEntity().getContent(), this.keyMaterial, this.position);
    }

    private static class DecryptingInputStream extends InputStream {

        private final InputStream in;
        private final ByteBuffer buff;
        private final Cipher cipher;
        private long chunkIndex;
        private boolean filled;

        public DecryptingInputStream(InputStream in, byte[] keyMaterial, long position) throws IOException {
            this.in = new BufferedInputStream(in);
            this.buff = ByteBuffer.allocate(BLOCK_SIZE);

            try {
                cipher = Cipher.getInstance("Blowfish/CBC/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE,
                        new SecretKeySpec(keyMaterial, "Blowfish"),
                        new IvParameterSpec(IV));
            } catch (Exception e) {
                throw new IOException("Failed to initialize Blowfish cipher", e);
            }

            chunkIndex = Math.max(0, position / BLOCK_SIZE);
            long remainingBytesInChunk = ((chunkIndex + 1) * BLOCK_SIZE) - position;
            if (remainingBytesInChunk < BLOCK_SIZE) {
                in.skip(remainingBytesInChunk);
                chunkIndex++;
            }
        }

        @Override
        public int read() throws IOException {
            if (this.filled && this.buff.hasRemaining()) {
                return this.buff.get() & 0xFF;
            }

            byte[] chunk = this.in.readNBytes(BLOCK_SIZE);
            if (chunk.length == 0) {
                return -1;
            }

            this.buff.clear();
            this.filled = true;

            if (this.chunkIndex % 3 > 0 || chunk.length < BLOCK_SIZE) {
                this.buff.put(chunk, 0, chunk.length);
            } else {
                try {
                    byte[] decrypted = this.cipher.doFinal(chunk);
                    this.buff.put(decrypted, 0, decrypted.length);
                } catch (Exception e) {
                    throw new IOException("Blowfish decryption failed at chunk " + chunkIndex, e);
                }
            }

            chunkIndex++;
            this.buff.flip();
            return this.buff.get() & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int totalRead = 0;
            for (int i = 0; i < len; i++) {
                int val = read();
                if (val == -1) {
                    return totalRead > 0 ? totalRead : -1;
                }
                b[off + i] = (byte) val;
                totalRead++;
            }
            return totalRead;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
