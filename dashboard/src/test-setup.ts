// Matchers such as toBeInTheDocument() for every test file.
import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// Unmount what each test rendered (and stop its polling). Testing Library only does it by itself
// when Vitest runs with globals, which this project does not use.
afterEach(cleanup)
