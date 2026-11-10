package com.beatofthedrum.alacdecoder;

/** 将 RAOP ALAC 帧解码为交错的 16 位小端 PCM，24 位音源保留其高 16 位。 */
public final class AlacDecoder {
    private final AlacFile file;
    private final int bits;
    private final int channels;
    private final int[] samples;

    /** RAOP 使用固定 Rice 参数，输出及帧声明的样本数都限制在 4096 以内。 */
    public AlacDecoder(int bits, int channels, int samplesPerFrame, int rate) {
        if ((bits != 16 && bits != 24) || channels < 1 || channels > 2
                || samplesPerFrame < 1 || samplesPerFrame > 4096 || rate < 8000 || rate > 96000) {
            throw new IllegalArgumentException("Invalid ALAC configuration");
        }
        this.bits = bits;
        this.channels = channels;
        file = AlacDecodeUtils.create_alac(bits, channels);
        file.setinfo_max_samples_per_frame = samplesPerFrame;
        file.setinfo_sample_size = bits;
        file.setinfo_rice_historymult = 40;
        file.setinfo_rice_initialhistory = 10;
        file.setinfo_rice_kmodifier = 14;
        file.setinfo_7f = channels;
        file.setinfo_80 = 255;
        file.setinfo_8a_rate = rate;
        samples = new int[4096 * channels * 3];
    }

    /** 只为三字节位读取预留尾部窥视空间，不接受超长、截断或声道不匹配的帧。 */
    public byte[] decode(byte[] frame) {
        if (frame.length < 3 || frame.length > 65536 || ((frame[0] & 255) >>> 5) != channels - 1) {
            throw new IllegalArgumentException("Invalid ALAC frame");
        }
        try {
            byte[] padded = java.util.Arrays.copyOf(frame, frame.length + 3);
            int size = AlacDecodeUtils.decode_frame(file, padded, samples, samples.length);
            if (size <= 0 || size > 4096 * channels * (bits / 8)
                    || file.ibIdx * 8 + file.input_buffer_bitaccumulator > frame.length * 8) {
                throw new IllegalArgumentException("Truncated ALAC frame");
            }
            int count = size / (bits / 8);
            byte[] pcm = new byte[count * 2];
            for (int i = 0; i < count; i++) {
                if (bits == 16) {
                    pcm[i * 2] = (byte) samples[i];
                    pcm[i * 2 + 1] = (byte) (samples[i] >>> 8);
                } else {
                    pcm[i * 2] = (byte) samples[i * 3 + 1];
                    pcm[i * 2 + 1] = (byte) samples[i * 3 + 2];
                }
            }
            return pcm;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid ALAC frame", e);
        }
    }
}
