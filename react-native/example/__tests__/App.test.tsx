/**
 * @format
 */

import ReactTestRenderer from 'react-test-renderer';
import App from '../App';

jest.mock(
  'react-native-safe-area-context',
  () => require('react-native-safe-area-context/jest/mock').default,
);

// The Jest preset reports iOS, where QartveloAds is unsupported: the screen must still render, with the
// banner reporting unsupported_platform instead of crashing.
test('renders the demo screen', async () => {
  let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
  await ReactTestRenderer.act(() => {
    renderer = ReactTestRenderer.create(<App />);
  });
  const text = JSON.stringify(renderer!.toJSON());
  expect(text).toContain('QartveloAds React Native example');
  expect(text).toContain('unsupported_platform');
});
