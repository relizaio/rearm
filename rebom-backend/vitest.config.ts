import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    globals: true,
    environment: 'node',
    testTimeout: 30000,
    hookTimeout: 30000,
    env: {
      // Enable OCI storage (will use mock in tests)
      OCI_STORAGE_ENABLED: 'true',
      MOCK_OCI: 'true',
      // Database config (uses defaults from utils.ts)
      POSTGRES_HOST: 'localhost',
      POSTGRES_PORT: '5438',
      POSTGRES_USER: 'postgres',
      POSTGRES_PASSWORD: 'password',
      POSTGRES_DATABASE: 'postgres'
    }
  }
});
