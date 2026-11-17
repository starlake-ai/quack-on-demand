//! SHA-256 (FIPS 180-4), for the bundle's path key (search spec 6.2: the lowercase hex SHA-256 of
//! the data file's absolute path), and HMAC-SHA256 (RFC 2104) over it, for the bundle MAC (spec 13,
//! format 3). Written here so the crate adds no hashing dependency; checked against the FIPS 180-4
//! example vectors below and the RFC 4231 vectors in tests/mac.rs.

const K: [u32; 64] = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
];

const H0: [u32; 8] = [
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
];

fn compress(state: &mut [u32; 8], block: &[u8; 64]) {
    let mut w = [0u32; 64];
    for (t, chunk) in block.chunks_exact(4).enumerate() {
        w[t] = u32::from_be_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]);
    }
    for t in 16..64 {
        let s0 = w[t - 15].rotate_right(7) ^ w[t - 15].rotate_right(18) ^ (w[t - 15] >> 3);
        let s1 = w[t - 2].rotate_right(17) ^ w[t - 2].rotate_right(19) ^ (w[t - 2] >> 10);
        w[t] = w[t - 16].wrapping_add(s0).wrapping_add(w[t - 7]).wrapping_add(s1);
    }
    let [mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut h] = *state;
    for t in 0..64 {
        let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
        let ch = (e & f) ^ (!e & g);
        let t1 = h.wrapping_add(s1).wrapping_add(ch).wrapping_add(K[t]).wrapping_add(w[t]);
        let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
        let maj = (a & b) ^ (a & c) ^ (b & c);
        let t2 = s0.wrapping_add(maj);
        h = g;
        g = f;
        f = e;
        e = d.wrapping_add(t1);
        d = c;
        c = b;
        b = a;
        a = t1.wrapping_add(t2);
    }
    for (s, v) in state.iter_mut().zip([a, b, c, d, e, f, g, h]) {
        *s = s.wrapping_add(v);
    }
}

/// SHA-256 over data given in parts.
pub struct Sha256 {
    state: [u32; 8],
    block: [u8; 64],
    filled: usize,
    total: u64,
}

impl Default for Sha256 {
    fn default() -> Self {
        Sha256::new()
    }
}

impl Sha256 {
    pub fn new() -> Sha256 {
        Sha256 { state: H0, block: [0u8; 64], filled: 0, total: 0 }
    }

    pub fn update(&mut self, mut data: &[u8]) {
        self.total = self.total.wrapping_add(data.len() as u64);
        if self.filled > 0 {
            let take = (64 - self.filled).min(data.len());
            self.block[self.filled..self.filled + take].copy_from_slice(&data[..take]);
            self.filled += take;
            data = &data[take..];
            if self.filled < 64 {
                return;
            }
            let block = self.block;
            compress(&mut self.state, &block);
            self.filled = 0;
        }
        let mut chunks = data.chunks_exact(64);
        for chunk in &mut chunks {
            let mut block = [0u8; 64];
            block.copy_from_slice(chunk);
            compress(&mut self.state, &block);
        }
        let rest = chunks.remainder();
        self.block[..rest.len()].copy_from_slice(rest);
        self.filled = rest.len();
    }

    pub fn finish(mut self) -> [u8; 32] {
        let bit_len = self.total.wrapping_mul(8);
        let rest = self.filled;
        let mut tail = [0u8; 128];
        tail[..rest].copy_from_slice(&self.block[..rest]);
        tail[rest] = 0x80;
        let tail_len = if rest < 56 { 64 } else { 128 };
        tail[tail_len - 8..tail_len].copy_from_slice(&bit_len.to_be_bytes());
        for block in tail[..tail_len].chunks_exact(64) {
            let mut b = [0u8; 64];
            b.copy_from_slice(block);
            compress(&mut self.state, &b);
        }
        let mut out = [0u8; 32];
        for (o, s) in out.chunks_exact_mut(4).zip(self.state.iter()) {
            o.copy_from_slice(&s.to_be_bytes());
        }
        out
    }
}

/// The SHA-256 digest of `data`.
pub fn digest(data: &[u8]) -> [u8; 32] {
    let mut h = Sha256::new();
    h.update(data);
    h.finish()
}

/// HMAC-SHA256 (RFC 2104, 64-byte block, ipad 0x36, opad 0x5c) of the concatenation of `parts`
/// under `key`. A key longer than the block is hashed first, as the RFC says.
pub fn hmac(key: &[u8], parts: &[&[u8]]) -> [u8; 32] {
    let mut k = [0u8; 64];
    if key.len() > 64 {
        k[..32].copy_from_slice(&digest(key));
    } else {
        k[..key.len()].copy_from_slice(key);
    }
    let mut pad = [0u8; 64];
    for (p, b) in pad.iter_mut().zip(k.iter()) {
        *p = b ^ 0x36;
    }
    let mut inner = Sha256::new();
    inner.update(&pad);
    for part in parts {
        inner.update(part);
    }
    let inner = inner.finish();
    for (p, b) in pad.iter_mut().zip(k.iter()) {
        *p = b ^ 0x5c;
    }
    let mut outer = Sha256::new();
    outer.update(&pad);
    outer.update(&inner);
    outer.finish()
}

/// Whether `a` and `b` are equal, in time independent of where they differ: every byte pair is
/// XORed into one accumulator, with no early exit.
pub fn ct_eq(a: &[u8; 32], b: &[u8; 32]) -> bool {
    let mut acc = 0u8;
    for i in 0..32 {
        acc |= std::hint::black_box(a[i] ^ b[i]);
    }
    std::hint::black_box(acc) == 0
}

/// Lowercase hex of the SHA-256 digest of `data`: 64 characters.
pub fn hex_digest(data: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut s = String::with_capacity(64);
    for b in digest(data) {
        s.push(HEX[(b >> 4) as usize] as char);
        s.push(HEX[(b & 15) as usize] as char);
    }
    s
}

#[cfg(test)]
mod tests {
    use super::{ct_eq, digest, hex_digest, Sha256};

    #[test]
    fn fips_180_4_vectors() {
        assert_eq!(hex_digest(b""), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assert_eq!(hex_digest(b"abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assert_eq!(
            hex_digest(b"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
        );
        let million_a = vec![b'a'; 1_000_000];
        assert_eq!(hex_digest(&million_a), "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0");
        // Lengths around the padding boundary (55, 56, 63, 64 bytes), against values computed with
        // shasum -a 256.
        for (n, want) in [
            (55usize, "9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318"),
            (56, "b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a"),
            (63, "7d3e74a05d7db15bce4ad9ec0658ea98e3f06eeecf16b4c6fff2da457ddc2f34"),
            (64, "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb"),
        ] {
            assert_eq!(hex_digest(&vec![b'a'; n]), want, "{n} bytes");
        }
    }

    #[test]
    fn parts_give_the_whole_digest() {
        let data: Vec<u8> = (0..1000u32).map(|i| (i * 7 + 3) as u8).collect();
        let whole = digest(&data);
        for cut in [0usize, 1, 55, 56, 63, 64, 65, 127, 128, 500, 999, 1000] {
            for second in [0usize, 1, 63, 64, 200] {
                let b = (cut + second).min(data.len());
                let mut h = Sha256::new();
                h.update(&data[..cut]);
                h.update(&data[cut..b]);
                h.update(&data[b..]);
                assert_eq!(h.finish(), whole, "cut {cut}, second {second}");
            }
        }
    }

    #[test]
    fn ct_eq_compares_every_byte() {
        let a = digest(b"a");
        assert!(ct_eq(&a, &a));
        for i in 0..32 {
            for bit in 0..8 {
                let mut b = a;
                b[i] ^= 1 << bit;
                assert!(!ct_eq(&a, &b), "byte {i} bit {bit}");
            }
        }
    }
}
