import React, { useState } from 'react';
import { sendMessage } from './api';

export default function ChatScreen() {
  const [message, setMessage] = useState('');
  const [loading, setLoading] = useState(false);
  const [answer, setAnswer] = useState(null);
  const [error, setError] = useState('');

  async function handleSubmit(event) {
    event.preventDefault();
    if (loading || !message.trim()) return;

    setLoading(true);
    setAnswer(null);
    setError('');
    try {
      setAnswer(await sendMessage(message));
    } catch (failure) {
      setError(failure.message || 'Could not reach the chat service. Please try again.');
    } finally {
      setLoading(false);
    }
  }

  return (
    <main>
      <h1>Garmin chat</h1>
      <p>Ask about your Garmin data and include a period. Each question starts fresh.</p>
      <form onSubmit={handleSubmit}>
        <label htmlFor="message">Your question</label>
        <div className="question-row">
          <input
            id="message"
            type="text"
            value={message}
            onChange={(event) => setMessage(event.target.value)}
            placeholder="Ask a question and include a date range…"
            disabled={loading}
          />
          <button type="submit" disabled={loading || !message.trim()}>Send</button>
        </div>
      </form>
      <section aria-label="Answer" aria-live="polite" aria-busy={loading}>
        {loading && <p>Preparing your answer…</p>}
        {answer && (
          <>
            <p className="date-range">
              {answer.startDate && answer.endDate
                ? `Readings: ${answer.startDate} through ${answer.endDate} (inclusive)`
                : 'Date range not selected.'}
            </p>
            <p className="answer">{answer.answer}</p>
          </>
        )}
        {!loading && !answer && !error && <p>Your answer will appear here.</p>}
      </section>
      {error && <p role="alert">{error}</p>}
    </main>
  );
}
