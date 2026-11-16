import React from 'react';
import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import ChatScreen from './ChatScreen';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

it('sends the exact input and shows the answer with its server-provided dates', async () => {
  const fetch = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => ({
      answer: 'Recorded metrics include resting heart rate and Body Battery.',
      startDate: '2026-11-01',
      endDate: '2026-11-30',
    }),
  });
  vi.stubGlobal('fetch', fetch);
  render(<ChatScreen />);
  const message = '  Which metrics are available in my Garmin data?  ';
  fireEvent.change(screen.getByLabelText('Your question'), { target: { value: message } });
  fireEvent.click(screen.getByText('Send'));

  expect(fetch).toHaveBeenCalledExactlyOnceWith('/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message }),
  });
  expect(await screen.findByText(
    'Recorded metrics include resting heart rate and Body Battery.',
  )).toBeTruthy();
  expect(screen.getByText('Readings: 2026-11-01 through 2026-11-30 (inclusive)')).toBeTruthy();
});

it('blocks blank and duplicate submissions while a request is loading', async () => {
  let finish;
  const fetch = vi.fn().mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
  vi.stubGlobal('fetch', fetch);
  render(<ChatScreen />);
  expect(screen.getByText('Send').disabled).toBe(true);
  fireEvent.change(screen.getByLabelText('Your question'), { target: { value: '   ' } });
  expect(screen.getByText('Send').disabled).toBe(true);
  fireEvent.change(screen.getByLabelText('Your question'), { target: { value: 'Steps?' } });
  fireEvent.click(screen.getByText('Send'));
  expect(screen.getByText('Send').disabled).toBe(true);
  fireEvent.click(screen.getByText('Send'));
  expect(fetch).toHaveBeenCalledTimes(1);
  finish({ ok: true, json: async () => ({ answer: 'Done', startDate: 'a', endDate: 'b' }) });
  await screen.findByText('Done');
});

it('shows a clarification answer without inventing a date range', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    ok: true,
    json: async () => ({
      answer: 'Which dates should I use?',
      startDate: null, endDate: null,
    }),
  }));
  render(<ChatScreen />);
  fireEvent.change(screen.getByLabelText('Your question'), { target: { value: 'Steps?' } });
  fireEvent.click(screen.getByText('Send'));

  expect(await screen.findByText('Which dates should I use?')).toBeTruthy();
  expect(screen.getByText('Date range not selected.')).toBeTruthy();
});

it('shows a model failure, clears the previous answer, and allows retry', async () => {
  const modelError = 'The chat model is initializing or unavailable. Please retry shortly.';
  const fetch = vi.fn()
    .mockResolvedValueOnce({
      ok: true,
      json: async () => ({ answer: 'Previous answer', startDate: 'a', endDate: 'b' }),
    })
    .mockResolvedValueOnce({
      ok: false,
      json: async () => ({ detail: modelError }),
    });
  vi.stubGlobal('fetch', fetch);
  render(<ChatScreen />);
  fireEvent.change(screen.getByLabelText('Your question'), { target: { value: 'Steps?' } });
  fireEvent.click(screen.getByText('Send'));
  await screen.findByText('Previous answer');
  fireEvent.click(screen.getByText('Send'));
  expect((await screen.findByRole('alert')).textContent).toBe(modelError);
  expect(screen.queryByText('Previous answer')).toBeNull();
  await waitFor(() => expect(screen.getByText('Send').disabled).toBe(false));
});

it('shows network and non-JSON HTTP failures', async () => {
  vi.stubGlobal('fetch', vi.fn()
    .mockRejectedValueOnce(new Error('Network unavailable'))
    .mockResolvedValueOnce({ ok: false, status: 502, json: async () => { throw new Error(); } }));
  render(<ChatScreen />);
  fireEvent.change(screen.getByLabelText('Your question'), { target: { value: 'Steps?' } });
  fireEvent.click(screen.getByText('Send'));
  expect((await screen.findByRole('alert')).textContent).toBe('Network unavailable');
  fireEvent.click(screen.getByText('Send'));
  expect((await screen.findByRole('alert')).textContent).toContain('Request failed (502)');
});
