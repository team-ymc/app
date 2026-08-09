// 챗 세션 히스토리 계약 3 operation (openapi.yaml listChatSessions 등, YMC-260).
import { authFetch } from './auth';
import { apiError } from './papers';
import type { ChatMessageStatus } from '../chat/chatState';

export interface ChatSessionSummary {
  sessionId: string;
  title: string;          // 첫 user 질문 앞 120자, 불변 (계약)
  lastMessageAt: string;  // 목록 정렬 키 (내림차순, 서버 정렬 신뢰)
  createdAt: string;
}

export interface ChatMessageItem {
  messageId: string;
  role: 'USER' | 'ASSISTANT';
  content: string | null; // GENERATING·FAILED assistant는 null (계약 — partial 저장 안 함)
  status: ChatMessageStatus;
  seq: number;            // 세션 내 단조 증가, 서버가 오름차순 정렬
  createdAt: string;
}

export async function listChatSessions(paperId: string): Promise<ChatSessionSummary[]> {
  const res = await authFetch(`/api/papers/${paperId}/chat/sessions`);
  if (!res.ok) throw await apiError(res);
  return res.json();
}

export async function listChatSessionMessages(paperId: string, sessionId: string): Promise<ChatMessageItem[]> {
  const res = await authFetch(`/api/papers/${paperId}/chat/sessions/${sessionId}/messages`);
  if (!res.ok) throw await apiError(res);
  return res.json();
}

export async function deleteChatSession(paperId: string, sessionId: string): Promise<void> {
  const res = await authFetch(`/api/papers/${paperId}/chat/sessions/${sessionId}`, { method: 'DELETE' });
  if (!res.ok) throw await apiError(res); // 204 기대
}
