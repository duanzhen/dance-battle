import request from '@/utils/request';
import { AxiosPromise } from 'axios';

export interface McpOAuthAuthorizeParams {
  response_type: string;
  client_id: string;
  redirect_uri: string;
  code_challenge: string;
  code_challenge_method: string;
  scope?: string;
  resource?: string;
  state?: string;
}

export interface McpOAuthApprovalResult {
  redirectUrl: string;
}

export function approveMcpAuthorization(data: McpOAuthAuthorizeParams): AxiosPromise<McpOAuthApprovalResult> {
  return request({
    url: '/mcp/authorize/approve',
    method: 'post',
    data,
    headers: {
      repeatSubmit: false
    }
  });
}
