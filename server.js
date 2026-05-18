const express = require('express');
const axios = require('axios');
const cors = require('cors');

const app = express();
app.use(cors());

// API key from environment variable (set in hosting panel or .env file)
const ICM_API_KEY = process.env.ICM_API_KEY || '';

if (!ICM_API_KEY) {
  console.error('WARNING: ICM_API_KEY not set!');
}

app.get('/auth/telegram', async (req, res) => {
  try {
    const { linked, icm_user_id, state, error } = req.query;
    
    if (error) {
      return res.status(400).json({ 
        success: false, 
        error: error,
        message: 'Telegram auth failed' 
      });
    }
    
    if (!linked || !icm_user_id) {
      return res.status(400).json({ 
        success: false, 
        error: 'missing_params',
        message: 'Missing linked or icm_user_id' 
      });
    }
    
    // Redirect back to app via custom scheme
    // App handles liquidmusicglass://oauth/icm?linked=1&icm_user_id=...
    const redirectUrl = `liquidmusicglass://oauth/icm?linked=1&icm_user_id=${icm_user_id}&state=${encodeURIComponent(state || '')}`;
    
    res.redirect(redirectUrl);
  } catch (error) {
    console.error('Auth error:', error.message);
    
    // Redirect back to app with error
    const errorUrl = `liquidmusicglass://oauth/icm?linked=0&error=${encodeURIComponent(error.message)}`;
    res.redirect(errorUrl);
  }
});

app.get('/health', (req, res) => {
  res.json({ 
    status: 'ok',
    timestamp: new Date().toISOString(),
    api_key_configured: !!ICM_API_KEY
  });
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log(`Auth server running on port ${PORT}`);
  console.log(`ICM API Key configured: ${ICM_API_KEY ? 'Yes' : 'NO - SET ICM_API_KEY ENV VAR!'}`);
});
