// 兼容入口：三浏览器矩阵配置已并入 playwright.config.js（`npx playwright test` 即跑矩阵）。
// 此文件仅做转发，避免两套配置各写一份导致漂移；保留是因为历史文档/脚本可能仍引用该文件名。
export { default } from './playwright.config.js';
