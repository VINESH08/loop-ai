// Home page initialization
document.addEventListener('DOMContentLoaded', async () => {
    // Check if user is authenticated
    const isAuth = await requireAuth();
    if (!isAuth) return;
    
    // Load user info
    await loadUserInfo();
    
    // Setup logout button
    setupLogoutButton();
});

// Load and display user information
async function loadUserInfo() {
    const userInfo = await getUserInfo();
    
    if (userInfo) {
        // Update user name
        const userNameEl = document.getElementById('userName');
        if (userNameEl) {
            userNameEl.textContent = userInfo.name || userInfo.given_name || 'User';
        }
        
        // Update user avatar
        const userPictureEl = document.getElementById('userPicture');
        const avatarFallbackEl = document.getElementById('avatarFallback');
        
        if (userInfo.picture) {
            if (userPictureEl) {
                userPictureEl.src = userInfo.picture;
                userPictureEl.style.display = 'block';
                userPictureEl.onerror = () => {
                    userPictureEl.style.display = 'none';
                    if (avatarFallbackEl) {
                        avatarFallbackEl.style.display = 'flex';
                    }
                };
            }
            if (avatarFallbackEl) {
                avatarFallbackEl.style.display = 'none';
            }
        } else {
            // Show fallback with initials
            if (avatarFallbackEl && userInfo.name) {
                const initials = userInfo.name.split(' ')
                    .map(n => n[0])
                    .join('')
                    .toUpperCase()
                    .slice(0, 2);
                avatarFallbackEl.textContent = initials;
            }
        }
        
        console.log('User info loaded:', userInfo);
    } else {
        console.error('Failed to load user info');
        // Redirect to login if user info can't be loaded
        window.location.href = 'index.html';
    }
}

// Setup logout button event listener
function setupLogoutButton() {
    const logoutBtn = document.getElementById('logoutBtn');
    if (logoutBtn) {
        logoutBtn.addEventListener('click', (e) => {
            e.preventDefault();
            logout();
        });
    }
}

