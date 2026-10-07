module.exports = {
  preset: '@react-native/jest-preset',
  // Test against the package sources, with a single copy of react / react-native.
  moduleNameMapper: {
    '^@qartvelo/react-native-ads$':
      '<rootDir>/../packages/react-native-qartvelo-ads/src/index.tsx',
    '^react$': '<rootDir>/node_modules/react',
    '^react-native$': '<rootDir>/node_modules/react-native',
  },
};
