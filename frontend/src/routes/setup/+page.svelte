<script>
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';

	const STORAGE_KEY = 'adoetzgpt_flash_backend_url';
	const RECENT_KEY = 'adoetzgpt_flash_recent_urls';
	const MAX_RECENT = 5;
	const PING_TIMEOUT = 8000;

	let backendUrl = '';
	let error = '';
	let connecting = false;
	let recentUrls = [];

	onMount(() => {
		// If already configured, redirect to main app
		const saved = localStorage.getItem(STORAGE_KEY);
		if (saved) {
			goto('/');
			return;
		}
		// Load recent URLs
		try {
			recentUrls = JSON.parse(localStorage.getItem(RECENT_KEY) || '[]');
		} catch {
			recentUrls = [];
		}
	});

	function normalizeUrl(raw) {
		let url = raw.trim().replace(/\/+$/, '');
		if (!url.startsWith('http://') && !url.startsWith('https://')) {
			url = 'http://' + url;
		}
		try {
			const u = new URL(url);
			return u.origin;
		} catch {
			return null;
		}
	}

	async function pingBackend(baseUrl) {
		const controller = new AbortController();
		const timer = setTimeout(() => controller.abort(), PING_TIMEOUT);
		try {
			await fetch(`${baseUrl}/health`, {
				method: 'GET',
				signal: controller.signal,
				mode: 'no-cors',
				cache: 'no-store'
			});
			clearTimeout(timer);
			return true;
		} catch (err) {
			clearTimeout(timer);
			if (err.name === 'AbortError') {
				throw new Error('Connection timed out. Is the server running?');
			}
			throw new Error('Server unreachable. Check the URL and your network.');
		}
	}

	function saveUrl(url) {
		localStorage.setItem(STORAGE_KEY, url);
		// Update recent list
		let recents = recentUrls.filter((u) => u !== url);
		recents.unshift(url);
		recents = recents.slice(0, MAX_RECENT);
		localStorage.setItem(RECENT_KEY, JSON.stringify(recents));
		// Also save via native bridge if available
		if (window.FlashNative) {
			window.FlashNative.saveBackendUrl(url);
		}
	}

	async function handleConnect() {
		error = '';
		if (!backendUrl.trim()) {
			error = 'Please enter a backend URL.';
			return;
		}
		const normalized = normalizeUrl(backendUrl);
		if (!normalized) {
			error = 'Invalid URL format. Example: https://chat.example.com';
			return;
		}
		connecting = true;
		try {
			await pingBackend(normalized);
			saveUrl(normalized);
			// Reload the app so constants.ts picks up the new backend URL
			window.location.href = '/';
		} catch (err) {
			error = err.message || 'Could not connect to backend.';
			connecting = false;
		}
	}

	function selectRecent(url) {
		backendUrl = url;
		handleConnect();
	}
</script>

<svelte:head>
	<title>AdoetzGPT Flash — Setup</title>
	<style>
		body { background: #0a0a0f !important; }
	</style>
</svelte:head>

<div class="setup-page">
	<div class="setup-bg">
		<div class="blob blob-1"></div>
		<div class="blob blob-2"></div>
	</div>

	<div class="setup-container">
		<div class="logo-section">
			<div class="logo-icon">
				<svg width="48" height="48" viewBox="0 0 48 48" fill="none">
					<circle cx="24" cy="24" r="22" stroke="url(#sg1)" stroke-width="2.5"/>
					<path d="M14 24C14 18.477 18.477 14 24 14s10 4.477 10 10-4.477 10-10 10" stroke="url(#sg2)" stroke-width="2.5" stroke-linecap="round"/>
					<circle cx="24" cy="24" r="4" fill="url(#sg1)"/>
					<defs>
						<linearGradient id="sg1" x1="0" y1="0" x2="48" y2="48" gradientUnits="userSpaceOnUse">
							<stop offset="0%" stop-color="#7c3aed"/><stop offset="100%" stop-color="#06b6d4"/>
						</linearGradient>
						<linearGradient id="sg2" x1="0" y1="0" x2="48" y2="48" gradientUnits="userSpaceOnUse">
							<stop offset="0%" stop-color="#06b6d4"/><stop offset="100%" stop-color="#7c3aed"/>
						</linearGradient>
					</defs>
				</svg>
			</div>
			<h1 class="app-title">AdoetzGPT Flash</h1>
			<p class="app-subtitle">Connect to your Open WebUI backend</p>
		</div>

		<div class="setup-card">
			<div class="card-header">
				<span class="step-badge">Setup</span>
				<h2>Backend Configuration</h2>
				<p>Enter the URL of your Open WebUI server to get started.</p>
			</div>

			<div class="form-group">
				<label for="backend-url">Backend URL</label>
				<input
					id="backend-url"
					type="url"
					bind:value={backendUrl}
					placeholder="https://your-openwebui-server.com"
					autocomplete="off"
					autocorrect="off"
					autocapitalize="off"
					spellcheck="false"
					on:keydown={(e) => e.key === 'Enter' && handleConnect()}
					disabled={connecting}
				/>
				<p class="input-hint">Example: <code>https://chat.example.com</code> or <code>http://192.168.1.10:8080</code></p>
			</div>

			{#if error}
				<div class="error-msg">{error}</div>
			{/if}

			<button class="btn-primary" on:click={handleConnect} disabled={connecting}>
				{connecting ? 'Connecting…' : 'Connect'}
				{#if !connecting}<span class="btn-arrow">→</span>{/if}
			</button>

			{#if recentUrls.length > 0}
				<div class="divider"><span>or</span></div>
				<div class="recent-section">
					<p class="recent-label">Recent connections</p>
					{#each recentUrls as url}
						<button class="recent-item" on:click={() => selectRecent(url)}>
							<span class="recent-url">{url}</span>
							<span class="recent-arrow">→</span>
						</button>
					{/each}
				</div>
			{/if}
		</div>

		<p class="footer-note">Your URL is stored locally on this device only.</p>
	</div>
</div>

<style>
	.setup-page {
		position: fixed;
		inset: 0;
		display: flex;
		justify-content: center;
		overflow-y: auto;
		background: #0a0a0f;
		color: #f0f0f5;
		font-family: 'Inter', system-ui, -apple-system, sans-serif;
		padding: env(safe-area-inset-top) env(safe-area-inset-right) env(safe-area-inset-bottom) env(safe-area-inset-left);
	}
	.setup-bg { position: fixed; inset: 0; pointer-events: none; overflow: hidden; }
	.blob { position: absolute; border-radius: 50%; filter: blur(80px); opacity: 0.18; animation: blobF 8s ease-in-out infinite; }
	.blob-1 { width: 400px; height: 400px; background: radial-gradient(circle, #7c3aed, transparent); top: -10%; right: -10%; }
	.blob-2 { width: 350px; height: 350px; background: radial-gradient(circle, #06b6d4, transparent); bottom: -5%; left: -10%; animation-delay: 3s; }
	@keyframes blobF { 0%,100% { transform: translate(0,0) scale(1); } 50% { transform: translate(20px,-20px) scale(1.05); } }

	.setup-container { position: relative; z-index: 1; width: 100%; max-width: 440px; padding: 40px 24px 60px; display: flex; flex-direction: column; align-items: center; gap: 24px; }
	.logo-section { display: flex; flex-direction: column; align-items: center; gap: 12px; margin-top: 24px; }
	.logo-icon { width: 72px; height: 72px; border-radius: 20px; background: rgba(124,58,237,0.12); border: 1px solid rgba(124,58,237,0.25); display: flex; align-items: center; justify-content: center; box-shadow: 0 0 40px rgba(124,58,237,0.2); }
	.app-title { font-size: 26px; font-weight: 700; background: linear-gradient(135deg, #f0f0f5, #a78bfa, #06b6d4); -webkit-background-clip: text; -webkit-text-fill-color: transparent; }
	.app-subtitle { font-size: 14px; color: #8a8a9a; }

	.setup-card { width: 100%; background: rgba(255,255,255,0.04); border: 1px solid rgba(255,255,255,0.08); border-radius: 16px; padding: 28px 24px; backdrop-filter: blur(20px); display: flex; flex-direction: column; gap: 20px; }
	.card-header { display: flex; flex-direction: column; gap: 8px; }
	.step-badge { display: inline-flex; padding: 4px 10px; border-radius: 20px; background: rgba(124,58,237,0.15); border: 1px solid rgba(124,58,237,0.3); font-size: 11px; font-weight: 600; color: #9d65f5; text-transform: uppercase; letter-spacing: 0.5px; width: fit-content; }
	.card-header h2 { font-size: 20px; font-weight: 600; }
	.card-header p { font-size: 13.5px; color: #8a8a9a; line-height: 1.5; }

	.form-group { display: flex; flex-direction: column; gap: 8px; }
	.form-group label { font-size: 13px; font-weight: 500; color: #8a8a9a; }
	.form-group input { width: 100%; background: rgba(255,255,255,0.05); border: 1px solid rgba(255,255,255,0.08); border-radius: 10px; padding: 14px; color: #f0f0f5; font-size: 14px; outline: none; transition: border-color 0.25s, box-shadow 0.25s; }
	.form-group input:focus { border-color: rgba(124,58,237,0.6); box-shadow: 0 0 0 3px rgba(124,58,237,0.12); }
	.form-group input::placeholder { color: #55556a; }
	.form-group input:disabled { opacity: 0.5; }
	.input-hint { font-size: 12px; color: #55556a; }
	.input-hint code { background: rgba(255,255,255,0.07); border-radius: 4px; padding: 1px 5px; font-size: 11.5px; color: #06b6d4; }

	.error-msg { background: rgba(239,68,68,0.1); border: 1px solid rgba(239,68,68,0.3); border-radius: 10px; padding: 12px 14px; font-size: 13px; color: #fca5a5; }

	.btn-primary { width: 100%; padding: 15px 24px; background: linear-gradient(135deg, #7c3aed, #5b21b6, #4338ca); border: none; border-radius: 10px; color: #fff; font-size: 15px; font-weight: 600; cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 8px; box-shadow: 0 4px 20px rgba(124,58,237,0.4); transition: transform 0.2s; }
	.btn-primary:active { transform: scale(0.98); }
	.btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }
	.btn-arrow { font-size: 18px; }

	.divider { display: flex; align-items: center; gap: 12px; color: #55556a; font-size: 12px; }
	.divider::before, .divider::after { content: ''; flex: 1; height: 1px; background: rgba(255,255,255,0.08); }

	.recent-section { display: flex; flex-direction: column; gap: 8px; }
	.recent-label { font-size: 12px; font-weight: 500; color: #55556a; text-transform: uppercase; letter-spacing: 0.5px; }
	.recent-item { display: flex; align-items: center; justify-content: space-between; padding: 12px 14px; background: rgba(255,255,255,0.03); border: 1px solid rgba(255,255,255,0.08); border-radius: 10px; cursor: pointer; color: #f0f0f5; font-size: 13px; transition: background 0.2s; }
	.recent-item:active { background: rgba(124,58,237,0.08); }
	.recent-url { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; text-align: left; }
	.recent-arrow { color: #55556a; flex-shrink: 0; }

	.footer-note { font-size: 12px; color: #55556a; text-align: center; }
</style>
