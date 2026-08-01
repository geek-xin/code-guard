/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        primary: {
          DEFAULT: '#F45113',
          hover: '#FF6A2A',
          soft: '#FFF0E6',
        },
        secondary: '#9EEBC5',
        accent: '#BEE7F8',
        pink: '#F8B8C8',
        gold: '#FFF176',
        success: '#18A96B',
        error: '#E23B2E',
        paper: '#F8F6F3',
        'paper-alt': '#FFF7D6',
        ink: {
          DEFAULT: '#161616',
          muted: '#6F6A64',
          subtle: '#9B948C',
        },
      },
      borderWidth: {
        chunky: '2px',
      },
      boxShadow: {
        chunky: '4px 4px 0 0 #111111',
        'chunky-sm': '3px 3px 0 0 #111111',
        'chunky-lg': '6px 6px 0 0 #111111',
      },
      fontFamily: {
        sans: ['Inter', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'Noto Sans CJK SC', 'ui-sans-serif', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'ui-monospace', 'SFMono-Regular', 'Menlo', 'Monaco', 'Consolas', 'monospace'],
      },
    },
  },
  plugins: [],
};
