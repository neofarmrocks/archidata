/** @file
 * @author Edouard DUPIN
 * @copyright 2024, Edouard DUPIN, all right reserved
 * @license MPL-2
 */
import { RestErrorResponse, isRestErrorResponse } from './model';

export enum HTTPRequestModel {
  ARCHIVE = 'ARCHIVE',
  CALL = 'CALL',
  DELETE = 'DELETE',
  HEAD = 'HEAD',
  GET = 'GET',
  OPTION = 'OPTION',
  PATCH = 'PATCH',
  POST = 'POST',
  PUT = 'PUT',
  RESTORE = 'RESTORE',
}
export enum HTTPMimeType {
  ALL = '*/*',
  CSV = 'text/csv',
  IMAGE = 'image/*',
  IMAGE_JPEG = 'image/jpeg',
  IMAGE_PNG = 'image/png',
  JSON = 'application/json',
  MULTIPART = 'multipart/form-data',
  OCTET_STREAM = 'application/octet-stream',
  TEXT_PLAIN = 'text/plain',
}

export interface RESTConfig {
  // base of the server: http(s)://my.server.org/plop/api/
  server: string;
  // Token to access of the data.
  token?: string;
  // api Token to access of the data.
  tokenApi?: string;
}

export interface RESTModel {
  // base of the local API request: "sheep/{id}".
  endPoint: string;
  // Type of the request.
  requestType?: HTTPRequestModel;
  // Input type requested.
  accept?: HTTPMimeType;
  // Content of the local data.
  contentType?: HTTPMimeType;
  // Mode of the TOKEN in URL or Header (?token:${tokenInUrl})
  tokenInUrl?: boolean;
}

export interface ModelResponseHttp {
  status: number;
  data: any;
  // Response headers; populated for JSON responses so paginated helpers can
  // read X-Total-Count and the RFC 5988 Link header.
  headers?: Headers;
}

/**
 * A paginated slice returned by a back-end endpoint that produces
 * Pagination<T>. The server emits the items as a plain list body plus
 * `X-Total-Count` and `Link` (RFC 5988) headers. `RESTRequestPaginatedJson`
 * assembles them into this object.
 */
export interface Pagination<TYPE> {
  items: TYPE[];
  total: number;
  offset: number;
  limit: number;
  hasNext: boolean;
  hasPrev: boolean;
  // Raw Link header value, for clients that want to follow rel="next" etc.
  linkHeader?: string;
}

export type ErrorRestApiCallback = (response: Response) => void;

let errorApiGlobalCallback: ErrorRestApiCallback | undefined = undefined;

export const setErrorApiGlobalCallback = (callback: ErrorRestApiCallback) => {
  errorApiGlobalCallback = callback;
};

function isNullOrUndefined(data: any): data is undefined | null {
  return data === undefined || data === null;
}

// generic progression callback
export type ProgressCallback = (count: number, total: number) => void;

export interface RESTAbort {
  abort?: () => boolean;
}

// Rest generic callback have a basic model to upload and download advancement.
export interface RESTCallbacks {
  progressUpload?: ProgressCallback;
  progressDownload?: ProgressCallback;
  abortHandle?: RESTAbort;
}

export interface RESTRequestType {
  restModel: RESTModel;
  restConfig: RESTConfig;
  data?: any;
  params?: object;
  queries?: object;
  headers?: any;
  callbacks?: RESTCallbacks;
}

function replaceAll(input, searchValue, replaceValue) {
  return input.split(searchValue).join(replaceValue);
}

function removeTrailingSlashes(input: string): string {
  if (isNullOrUndefined(input)) {
    return 'undefined';
  }
  return input.replace(/\/+$/, '');
}
function removeLeadingSlashes(input: string): string {
  if (isNullOrUndefined(input)) {
    return '';
  }
  return input.replace(/^\/+/, '');
}

export function RESTUrl({
  restModel,
  restConfig,
  params,
  queries,
}: RESTRequestType): string {
  // Create the URL PATH:
  let generateUrl = `${removeTrailingSlashes(
    restConfig.server
  )}/${removeLeadingSlashes(restModel.endPoint)}`;
  if (params !== undefined) {
    for (let key of Object.keys(params)) {
      generateUrl = replaceAll(generateUrl, `{${key}}`, `${params[key]}`);
    }
  }
  if (
    queries === undefined &&
    ((restConfig.token === undefined && restConfig.tokenApi === undefined) || restModel.tokenInUrl !== true)
  ) {
    return generateUrl;
  }
  const searchParams = new URLSearchParams();
  if (queries !== undefined) {
    for (let key of Object.keys(queries)) {
      const value = queries[key];
      if (Array.isArray(value)) {
        for (const element of value) {
          searchParams.append(`${key}`, `${element}`);
        }
      } else {
        searchParams.append(`${key}`, `${value}`);
      }
    }
  }
  if (restConfig.token !== undefined && restModel.tokenInUrl === true) {
    searchParams.append('Authorization', `Bearer ${restConfig.token}`);
  }
  if (restConfig.tokenApi !== undefined && restModel.tokenInUrl === true) {
    searchParams.append('Authorization', `ApiKey ${restConfig.tokenApi}`);
  }
  return generateUrl + '?' + searchParams.toString();
}

export function fetchProgress(
  generateUrl: string,
  {
    method,
    headers,
    body,
  }: {
    method: HTTPRequestModel;
    headers: any;
    body: any;
  },
  { progressUpload, progressDownload, abortHandle }: RESTCallbacks
): Promise<Response> {
  const xhr: {
    io?: XMLHttpRequest;
  } = {
    io: new XMLHttpRequest(),
  };
  return new Promise((resolve, reject) => {
    // Stream the upload progress
    if (progressUpload) {
      xhr.io?.upload.addEventListener('progress', (dataEvent) => {
        if (dataEvent.lengthComputable) {
          progressUpload(dataEvent.loaded, dataEvent.total);
        }
      });
    }
    // Stream the download progress
    if (progressDownload) {
      xhr.io?.addEventListener('progress', (dataEvent) => {
        if (dataEvent.lengthComputable) {
          progressDownload(dataEvent.loaded, dataEvent.total);
        }
      });
    }
    if (abortHandle) {
      abortHandle.abort = () => {
        if (xhr.io) {
          console.log(`Request abort on the XMLHttpRequest: ${generateUrl}`);
          xhr.io.abort();
          return true;
        }
        console.log(
          `Request abort (FAIL) on the XMLHttpRequest: ${generateUrl}`
        );
        return false;
      };
    }
    // Check if we have an internal Fail:
    xhr.io?.addEventListener('error', () => {
      xhr.io = undefined;
      reject(new TypeError('Failed to fetch'));
    });

    // Capture the end of the stream
    xhr.io?.addEventListener('loadend', () => {
      if (xhr.io?.readyState !== XMLHttpRequest.DONE) {
        return;
      }
      if (xhr.io?.status === 0) {
        //the stream has been aborted
        reject(new TypeError('Fetch has been aborted'));
        return;
      }
      // Stream is ended, transform in a generic response:
      const response = new Response(xhr.io.response, {
        status: xhr.io.status,
        statusText: xhr.io.statusText,
      });
      const headersArray = replaceAll(
        xhr.io.getAllResponseHeaders().trim(),
        '\r\n',
        '\n'
      ).split('\n');
      headersArray.forEach(function (header) {
        const firstColonIndex = header.indexOf(':');
        if (firstColonIndex !== -1) {
          const key = header.substring(0, firstColonIndex).trim();
          const value = header.substring(firstColonIndex + 1).trim();
          response.headers.set(key, value);
        } else {
          response.headers.set(header, '');
        }
      });
      xhr.io = undefined;
      resolve(response);
    });
    xhr.io?.open(method, generateUrl, true);
    if (!isNullOrUndefined(headers)) {
      for (const [key, value] of Object.entries(headers)) {
        xhr.io?.setRequestHeader(key, value as string);
      }
    }
    xhr.io?.send(body);
  });
}

export function RESTRequest({
  restModel,
  restConfig,
  data,
  params,
  queries,
  headers = {},
  callbacks,
}: RESTRequestType): Promise<ModelResponseHttp> {
  // Create the URL PATH:
  let generateUrl = RESTUrl({ restModel, restConfig, data, params, queries });
  if (restConfig.token !== undefined && restModel.tokenInUrl !== true) {
    headers['Authorization'] = `Bearer ${restConfig.token}`;
  }
  if (restConfig.tokenApi !== undefined && restModel.tokenInUrl !== true) {
    headers['Authorization'] = `ApiKey ${restConfig.tokenApi}`;
  }
  if (restModel.accept !== undefined) {
    headers['Accept'] = restModel.accept;
  }
  if (
    restModel.requestType !== HTTPRequestModel.GET &&
    restModel.requestType !== HTTPRequestModel.ARCHIVE &&
    restModel.requestType !== HTTPRequestModel.RESTORE
  ) {
    // if Get we have not a content type, the body is empty
    if (
      restModel.contentType !== HTTPMimeType.MULTIPART &&
      restModel.contentType !== undefined
    ) {
      // special case of multi-part ==> no content type otherwise the browser does not set the ";bundary=--****"
      headers['Content-Type'] = restModel.contentType;
    }
  }
  let body = data;
  if (restModel.contentType === HTTPMimeType.JSON) {
    body = JSON.stringify(data);
  } else if (restModel.contentType === HTTPMimeType.MULTIPART) {
    const formData = new FormData();
    for (const name in data) {
      formData.append(name, data[name]);
    }
    body = formData;
  }
  return new Promise((resolve, reject) => {
    let action: undefined | Promise<Response> = undefined;
    if (
      isNullOrUndefined(callbacks) ||
      (isNullOrUndefined(callbacks.progressDownload) &&
        isNullOrUndefined(callbacks.progressUpload) &&
        isNullOrUndefined(callbacks.abortHandle))
    ) {
      // No information needed: call the generic fetch interface
      action = fetch(generateUrl, {
        method: restModel.requestType,
        headers,
        body,
      });
    } else {
      // need progression information: call old fetch model (XMLHttpRequest) that permit to keep % upload and % download for HTTP1.x
      action = fetchProgress(
        generateUrl,
        {
          method: restModel.requestType ?? HTTPRequestModel.GET,
          headers,
          body,
        },
        callbacks
      );
    }
    action
      .then((response: Response) => {
        if (
          errorApiGlobalCallback &&
          400 <= response.status &&
          response.status <= 499
        ) {
          // Detect an error and trigger the generic error callback:
          errorApiGlobalCallback(response);
        }
        if (response.status >= 200 && response.status <= 299) {
          const contentType = response.headers.get('Content-Type');
          if (
            !isNullOrUndefined(restModel.accept) &&
            restModel.accept !== contentType
          ) {
            reject({
              name: 'Model accept type incompatible',
              time: Date().toString(),
              status: 901,
              message: `REST Content type are not compatible: ${restModel.accept} != ${contentType}`,
              statusMessage: 'Fetch error',
              error: 'rest-tools.ts Wrong type in the message return type',
            } as RestErrorResponse);
          } else if (contentType === HTTPMimeType.JSON) {
            response
              .json()
              .then((value: any) => {
                resolve({
                  status: response.status,
                  data: value,
                  headers: response.headers,
                });
              })
              .catch((reason: Error) => {
                reject({
                  name: 'API serialization error',
                  time: Date().toString(),
                  status: 902,
                  message: `REST parse json fail: ${reason}`,
                  statusMessage: 'Fetch parse error',
                  error: 'rest-tools.ts Wrong message model to parse',
                } as RestErrorResponse);
              });
          } else {
            resolve({
              status: response.status,
              data: response.body,
              headers: response.headers,
            });
          }
        } else {
          // the answer is not correct not a 2XX
          // clone the response to keep the raw data if case of error:
          response
            .clone()
            .json()
            .then((value: any) => {
              if (isRestErrorResponse(value)) {
                reject(value);
              } else {
                response
                  .text()
                  .then((dataError: string) => {
                    reject({
                      name: 'API serialization error',
                      time: Date().toString(),
                      status: 903,
                      message: `REST parse error json with wrong type fail. ${dataError}`,
                      statusMessage: 'Fetch parse error',
                      error: 'rest-tools.ts Wrong message model to parse',
                    } as RestErrorResponse);
                  })
                  .catch((reason: any) => {
                    reject({
                      name: 'API serialization error',
                      time: Date().toString(),
                      status: response.status,
                      message: `unmanaged error model: ??? with error: ${reason}`,
                      statusMessage: 'Fetch ERROR parse error',
                      error: 'rest-tools.ts Wrong message model to parse',
                    } as RestErrorResponse);
                  });
              }
            })
            .catch((reason: Error) => {
              response
                .text()
                .then((dataError: string) => {
                  reject({
                    name: 'API serialization error',
                    time: Date().toString(),
                    status: response.status,
                    message: `unmanaged error model: ${dataError} with error: ${reason}`,
                    statusMessage: 'Fetch ERROR TEXT parse error',
                    error: 'rest-tools.ts Wrong message model to parse',
                  } as RestErrorResponse);
                })
                .catch((reason: any) => {
                  reject({
                    name: 'API serialization error',
                    time: Date().toString(),
                    status: response.status,
                    message: `unmanaged error model: ??? with error: ${reason}`,
                    statusMessage: 'Fetch ERROR TEXT FAIL',
                    error: 'rest-tools.ts Wrong message model to parse',
                  } as RestErrorResponse);
                });
            });
        }
      })
      .catch((error: Error) => {
        if (isRestErrorResponse(error)) {
          reject(error);
        } else {
          reject({
            name: 'Request fail',
            time: Date(),
            status: 999,
            message: error,
            statusMessage: 'Fetch catch error',
            error: 'rest-tools.ts detect an error in the fetch request',
          });
        }
      });
  });
}

export function RESTRequestJson<TYPE>(
  request: RESTRequestType,
  checker?: (data: any) => data is TYPE
): Promise<TYPE> {
  return new Promise((resolve, reject) => {
    RESTRequest(request)
      .then((value: ModelResponseHttp) => {
        if (isNullOrUndefined(checker)) {
          console.log(`Have no check of MODEL in API: ${RESTUrl(request)}`);
          resolve(value.data);
        } else if (checker === undefined || checker(value.data)) {
          resolve(value.data);
        } else {
          reject({
            name: 'Model check fail',
            time: Date().toString(),
            status: 950,
            error: 'REST Fail to verify the data',
            statusMessage: 'API cast ERROR',
            message: 'api.ts Check type as fail',
          } as RestErrorResponse);
        }
      })
      .catch((reason: RestErrorResponse) => {
        reject(reason);
      });
  });
}

/**
 * Variant of {@link RESTRequestJson} for paginated endpoints.
 *
 * The server returns a plain `TYPE[]` body and exposes pagination metadata
 * through the `X-Total-Count` and `Link` (RFC 5988) HTTP headers. This helper
 * assembles them into a {@link Pagination} object.
 *
 * The caller can also pass `page.offset` / `page.limit` on a separate
 * argument: they are mapped to the `X-Pagination-Offset` /
 * `X-Pagination-Limit` HTTP headers on the request.
 *
 * @typeParam TYPE - the element type of the paginated list
 * @param request - the underlying REST request (path, queries, body, …)
 * @param checker - optional runtime validator for the items list
 * @param page - optional pagination request input (offset / limit)
 */
export function RESTRequestPaginatedJson<TYPE>(
  request: RESTRequestType,
  checker?: (data: any) => data is TYPE[],
  page?: { offset?: number; limit?: number }
): Promise<Pagination<TYPE>> {
  const requestWithPagination: RESTRequestType = {
    ...request,
    headers: {
      ...(request.headers ?? {}),
      ...(page?.offset !== undefined && {
        'X-Pagination-Offset': String(page.offset),
      }),
      ...(page?.limit !== undefined && {
        'X-Pagination-Limit': String(page.limit),
      }),
    },
  };
  return new Promise((resolve, reject) => {
    RESTRequest(requestWithPagination)
      .then((value: ModelResponseHttp) => {
        if (!Array.isArray(value.data)) {
          reject({
            name: 'Pagination shape error',
            time: Date().toString(),
            status: 951,
            error: 'Expected an array body for paginated endpoint',
            statusMessage: 'API cast ERROR',
            message: 'rest-tools.ts paginated body is not an array',
          } as RestErrorResponse);
          return;
        }
        if (!isNullOrUndefined(checker) && !checker(value.data)) {
          reject({
            name: 'Model check fail',
            time: Date().toString(),
            status: 950,
            error: 'REST Fail to verify the data',
            statusMessage: 'API cast ERROR',
            message: 'rest-tools.ts paginated items check failed',
          } as RestErrorResponse);
          return;
        }
        const items = value.data as TYPE[];
        const headers = value.headers;
        const totalHeader = headers?.get('X-Total-Count');
        const total =
          totalHeader !== null && totalHeader !== undefined
            ? Number(totalHeader)
            : items.length;
        const linkHeader = headers?.get('Link') ?? undefined;
        const offset = page?.offset ?? 0;
        const limit = page?.limit ?? items.length || 1;
        resolve({
          items,
          total,
          offset,
          limit,
          hasNext: linkHeader !== undefined && linkHeader.includes('rel="next"'),
          hasPrev: linkHeader !== undefined && linkHeader.includes('rel="prev"'),
          linkHeader,
        });
      })
      .catch((reason: RestErrorResponse) => {
        reject(reason);
      });
  });
}

export function RESTRequestVoid(request: RESTRequestType): Promise<void> {
  return new Promise((resolve, reject) => {
    RESTRequest(request)
      .then((value: ModelResponseHttp) => {
        resolve();
      })
      .catch((reason: RestErrorResponse) => {
        reject(reason);
      });
  });
}
