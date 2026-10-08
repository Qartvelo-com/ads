/**
 * @format
 */

import ReactTestRenderer from 'react-test-renderer';
import App from '../App';

jest.mock(
  'react-native-safe-area-context',
  () => require('react-native-safe-area-context/jest/mock').default,
);

// JavaScript renderer tests do not link native components. Exercise native ads in the iOS example.
jest.mock('@qartvelo/react-native-ads', () => ({
  ...jest.requireActual('@qartvelo/react-native-ads'),
  QartveloAdsBanner: require('react-native').View,
}));
test('renders the demo screen', async () => {
  let renderer: ReactTestRenderer.ReactTestRenderer | undefined;
  await ReactTestRenderer.act(() => {
    renderer = ReactTestRenderer.create(<App />);
  });
  const text = JSON.stringify(renderer!.toJSON());
  expect(text).toContain('QartveloAds React Native example');
  expect(text).toContain('Waiting for initialize()');
});
