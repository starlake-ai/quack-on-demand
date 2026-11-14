import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App.tsx';
// Self-hosted faces of the Console theme (no request leaves for a font CDN).
import '@fontsource/host-grotesk/400.css';
import '@fontsource/host-grotesk/500.css';
import '@fontsource/atkinson-hyperlegible-mono/400.css';
import '@fontsource/atkinson-hyperlegible-mono/500.css';
import './styles.css';

// styles.css hides the closed mobile drawer only under .js, so nothing depends on a script to be visible.
document.documentElement.classList.add('js');

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
