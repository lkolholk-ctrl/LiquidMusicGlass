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
    
    const partnerUserId = `tg_${icm_user_id}`;
    
    // Issue ICM session token (server-side only, API key never exposed to client)
    const sessionRes = await axios.post(
      'https://byicloud.online/api/partner/session/issue',
      {
        partner_user_id: partnerUserId,
        hide_explicit: false
      },
      {
        headers: {
          'X-Partner-Key': ICM_API_KEY,
          'Content-Type': 'application/json'
        },
        timeout: 10000
      }
    );
    
    // Redirect back to app with session token
    // Using custom URL scheme or deep link
    const redirectUrl = `liquidmusicglass://auth/telegram?success=1&token=${encodeURIComponent(sessionRes.data.partner_session_token)}&expires_in=${sessionRes.data.expires_in}&icm_user_id=${icm_user_id}&state=${encodeURIComponent(state || '')}`;
    
    res.redirect(redirectUrl);
  } catch (error) {
    console.error('Auth error:', error.message);
    
    // Redirect back to app with error
    const errorUrl = `liquidmusicglass://auth/telegram?success=0&error=${encodeURIComponent(error.message)}`;
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
