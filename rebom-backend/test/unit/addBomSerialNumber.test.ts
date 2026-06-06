import { describe, it, expect } from 'vitest';
import { addBom } from '../../src/services/bom/bomAddService';

// A serialNumber-less CycloneDX BOM must be rejected with a clear error before
// any storage happens — rebom does not mint serialNumbers on its side.
describe('addBom serialNumber requirement', () => {
	const bomWithout = {
		bomFormat: 'CycloneDX', specVersion: '1.5', version: 1,
		metadata: { timestamp: '2026-06-04T01:00:00Z' },
		components: [{ type: 'library', 'bom-ref': 'c1', name: 'left-pad', version: '1.3.0', purl: 'pkg:npm/left-pad@1.3.0' }],
	};

	it('rejects a CycloneDX BOM that has no serialNumber', async () => {
		await expect(
			addBom({ bomInput: { format: 'CYCLONEDX', org: '00000000-0000-0000-0000-000000000000', bom: bomWithout } } as any)
		).rejects.toThrow(/serialNumber/i);
	});
});
