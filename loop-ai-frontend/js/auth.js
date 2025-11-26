// Configuration
const API_BASE_URL = 'http://localhost:8080';

// Check authentication status
async function checkAuth() {
    try {
        const response = await fetch(`${API_BASE_URL}/api/auth/check`, {
            method: 'GET',
            credentials: 'include',
            headers: {
                'Accept': 'application/json'
            }
        });
        
        if (response.ok) {
            const data = await response.json();
            return data;
        }
        return { authenticated: false };
    } catch (error) {
        console.error('Auth check failed:', error);
        return { authenticated: false };
    }
}

// Get user info
async function getUserInfo() {
    try {
        const response = await fetch(`${API_BASE_URL}/api/auth/user`, {
            method: 'GET',
            credentials: 'include',
            headers: {
                'Accept': 'application/json'
            }
        });
        
        if (response.ok) {
            return await response.json();
        }
        return null;
    } catch (error) {
        console.error('Failed to get user info:', error);
        return null;
    }
}

// Logout function
function logout() {
    window.location.href = `${API_BASE_URL}/logout`;
}

// Redirect to login if not authenticated (for protected pages)
async function requireAuth() {
    const auth = await checkAuth();
    if (!auth.authenticated) {
        window.location.href = 'index.html';
        return false;
    }
    return true;
}

// Redirect to home if already authenticated (for login page)
async function redirectIfAuthenticated() {
    const auth = await checkAuth();
    if (auth.authenticated) {
        window.location.href = 'home.html';
        return true;
    }
    return false;
}

// Initialize based on current page
document.addEventListener('DOMContentLoaded', async () => {
    const currentPage = window.location.pathname;
    
    if (currentPage.includes('index.html') || currentPage.endsWith('/') || currentPage === '') {
        // On login page - redirect if already logged in
        await redirectIfAuthenticated();
    }
});

