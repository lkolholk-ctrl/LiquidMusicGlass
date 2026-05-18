const express = require('express');
const axios = require('axios');
const cors = require('cors');

const app = express();
app.use(cors());

const ICM_API_KEY = process.env.ICM_API_KEY || '';

// Always redirect back to the app via custom scheme so the app
// can react to every outcome (success / error / missing params).
app.get('/auth/telegram', (req, res) => {
  const { linked, icm_user_id, state, error } = req.query;
  const s = encodeURIComponent(state || '');

  // ICM/Telegram auth error -> forward to app
  if (error) {
    console.warn('ICM auth error:', error);
    return res.redirect(
      `liquidmusicglass://oauth/icm?linked=0&state=${s}&error=${encodeURIComponent(error)}`
    );
  }

  // Missing required params -> forward as error so the app shows a message
  if (linked !== '1' || !icm_user_id) {
    console.warn('ICM auth missing params:', { linked, icm_user_id });
    return res.redirect(
      `liquidmusicglass://oauth/icm?linked=0&state=${s}&error=missing_params`
    );
  }

  // Success
  return res.redirect(
    `liquidmusicglass://oauth/icm?linked=1&icm_user_id=${encodeURIComponent(icm_user_id)}&state=${s}`
  );
});

app.get('/health', (req, res) => {
  res.json({
    status: 'ok',
    timestamp: new Date().toISOString(),
    api_key_configured: !!ICM_API_KEY,
  });
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log(`Auth server running on port ${PORT}`);
});
