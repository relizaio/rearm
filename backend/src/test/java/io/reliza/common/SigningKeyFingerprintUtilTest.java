/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;

/**
 * Fixtures are throwaway keys generated with gpg/ssh-keygen; expected values
 * are the fingerprints those tools printed at generation time, so these tests
 * pin our derivation against the canonical implementations. They also guard
 * the Bouncy Castle PGP parsing path across bcpg version bumps (the bcprov
 * 1.85 bump silently broke empty-DN certificate parsing in KubesealService —
 * this is the equivalent canary for SigningKeyFingerprintUtil).
 */
public class SigningKeyFingerprintUtilTest {

	private static final String ED25519_GPG_PUB = """
			-----BEGIN PGP PUBLIC KEY BLOCK-----

			mDMEalQtyxYJKwYBBAHaRw8BAQdAtfZ1i9+ML7KuVriVjoFaASmNOYg0Bl5kKrt6
			gdLN1dO0E2JjMTg1LXRlc3QgPHRAdC5pbz6ImQQTFgoAQRYhBFTi1tbbxvA3YjwG
			57ciWeybL4M3BQJqVC3LAhsDBQkFo5qABQsJCAcCAiICBhUKCQgLAgQWAgMBAh4H
			AheAAAoJELciWeybL4M3UakBAPnnp63OpFJkQ9aB532DzkKG7Q+hf8yDCEPQ/e8J
			GnRjAQDFXCK72UF3h/9Jnq9bNmHDpHieoFur/tnTxK/ankm/Dw==
			=vbTy
			-----END PGP PUBLIC KEY BLOCK-----
			""";

	private static final String RSA3072_GPG_PUB = """
			-----BEGIN PGP PUBLIC KEY BLOCK-----

			mQGNBGpULekBDACtN9LBnQqaaF33/4rkH6aEX6Hg4OpjCT4d4TRx/GIRCSJCoTWw
			skLz1ThVTcF6xInh2FifxOBg/mMM1hw1tltnBkkb/PIneAsZTACNqbjKHJXUt9jm
			+6BL3jQIqTrB37t+OhX0RfEbJuQM9zNUBbWW6iKdQc5yt45fyJ9KsTR5TrCQOCnK
			k0HQ7ecu2ZCBsiDbkBDnBKCMsFaXl+qWsAYb9oHpbWLPXbpagPbuDlTYvs5PkbAt
			i7M0YllZy1BhkXrpqMcJRVAaGwipNZ3H6nlB9UNNAt/c43TEj30W1dyhf332DPDP
			qOsuU5y4HViHDADy+SDWgh4o4dlFFlPpdzWEbWstI/WZxOmeNVphHsbsSvFMNfpj
			qSNMKm0Q5rzUZDhjxgkwQLvDvlGqzIV/scVe8jn/riGdOmDEG7gqC7qsQYFazRjh
			jozro4/SXiqbczsjq+9trjKJ2fz9WThygLpeB0pWPEq3e6NNsHAsqFKIFcD/U8yU
			rhE+KOKpD2tTdCEAEQEAAbQSYmMxODUtcnNhIDxyQHQuaW8+iQHXBBMBCgBBFiEE
			oGmRJ2nBmopH+EGeotbM6LJcVCIFAmpULekCGwMFCQWjmoAFCwkIBwICIgIGFQoJ
			CAsCBBYCAwECHgcCF4AACgkQotbM6LJcVCIpFAv8DOkB68BMKGHEcmVpcYPhPeop
			UDFz/yIGIXhbg4V0FcljUdc41FLOAbD7gjIRlPv7lMaSsPAIIPAHMl3nqi4kcodv
			jZW+R8Ks7LHc3y94NS0/3mNbiDoSfI+BuA66sBNAVf/WD1qTqx/V5qvgqgx64BPW
			tbQpaH6A6ruIS59xzqwyw5K+QRiFkIyRQIlX78SADX5f20CnMl9kyXFtOLjdsIO3
			nYmcfLHW2Tw6ekqoVVSJdkTc8CQ6dWlVXeS2SlKmt+OKX4vM7C7xxrUFLLRweJV7
			UWO1OmHeQIvywwRgOrAn54IVE6g3vPMuFD9Dvj+FlNz8vy7ps7Tkpotu06/Y55Vr
			NGhOetXyNVbVDrcH+X20IVwbuS9sy/CkI7UWpxTMaZG5TqDQ0mHpDky1jmyzsb1L
			zWsOZ4VI5BUhtrVbi0/wgWgJVWZfzlqN4ib7zWcUAn6KZwhUjJS+m9hHBVAbqQpH
			QjSWH9Yp37OLh9E4ra5EL/V11dm+lRiEkpnEucqV
			=2Eoy
			-----END PGP PUBLIC KEY BLOCK-----
			""";

	private static final String SSH_ED25519_PUB =
			"ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMvFSrlnLrJWUHsyxU/M4TeAGTd/OPDzLuX+TP92lHOv test";

	@Test
	void derivesGpgLongKeyIdForEd25519() throws Exception {
		assertEquals("B72259EC9B2F8337", SigningKeyFingerprintUtil.deriveGpgLongKeyId(ED25519_GPG_PUB));
	}

	@Test
	void derivesGpgLongKeyIdForRsa3072() throws Exception {
		assertEquals("A2D6CCE8B25C5422", SigningKeyFingerprintUtil.deriveGpgLongKeyId(RSA3072_GPG_PUB));
	}

	@Test
	void derivesSshFingerprintMatchingSshKeygen() throws Exception {
		assertEquals("SHA256:UYfLaB5Zsa0cvZ+Eas2X4CbjeosrBBh16NOZiuEp9HI",
				SigningKeyFingerprintUtil.deriveSshFingerprint(SSH_ED25519_PUB));
	}

	@Test
	void rejectsGarbageGpgInput() {
		assertThrows(RelizaException.class,
				() -> SigningKeyFingerprintUtil.deriveGpgLongKeyId("not a key"));
	}
}
