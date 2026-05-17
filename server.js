const express = require('express');
const axios = require('axios');
const cors = require('cors');

const app = express();
app.use(cors());

const ICM_API_KEY = 'YOUR_ICM_API_KEY_HERE';

app.get('/auth/telegram', async (req, res) => {
  try {
    const data = req.query;
    
    // ICM API already verified Telegram auth
    // Just extract user data
    const userId = data.id;
    const username = data.username;
    const firstName = data.first_name;
    
    if (!userId) {
      return res.status(400).json({ error: 'Missing user id' });
    }
    
    const partnerUserId = `tg_${userId}`;
    
    // Issue ICM session token
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
        }
      }
    );
    
    res.json({
      success: true,
      token: sessionRes.data.partner_session_token,
      expires_in: sessionRes.data.expires_in,
      user: {
        id: userId,
        first_name: firstName,
        username: username
      }
    });
  } catch (error) {
    console.error('Auth error:', error.message);
    res.status(500).json({ error: 'Authentication failed' });
  }
});

app.get('/health', (req, res) => {
  res.json({ status: 'ok' });
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log(`Auth server running on port ${PORT}`);
});
