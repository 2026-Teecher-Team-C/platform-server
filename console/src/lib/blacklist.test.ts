import { expect, it } from 'vitest'
import { ApiError } from './api/client'
import { blacklistErrorMessage, getBlacklistQueryAfterCreate, getBlacklistQueryAfterStatusChange, validateBlacklistForm } from './blacklist'

it('등록은 앞뒤 공백을 제외한 64자리 해시와 비어 있지 않은 사유만 허용한다', () => {
  expect(validateBlacklistForm(' '+ 'AB'.repeat(32)+' ', ' 확인 ')).toBe('')
  expect(validateBlacklistForm('abc', '확인')).toContain('64자리')
  expect(validateBlacklistForm('z'.repeat(64), '확인')).toContain('16진수')
  expect(validateBlacklistForm('a'.repeat(64), '  ')).toContain('사유')
})

it('중복, 사라진 항목, 권한과 입력 오류를 구분하고 네트워크 실패는 재조회하도록 안내한다', () => {
  expect(blacklistErrorMessage(new ApiError(409, { message: '화이트리스트에 등록된 해시입니다.' }))).toContain('화이트리스트')
  expect(blacklistErrorMessage(new ApiError(404, {}))).toContain('새로고침')
  expect(blacklistErrorMessage(new ApiError(403, {}))).toContain('권한')
  expect(blacklistErrorMessage(new ApiError(400, {}))).toContain('입력')
  expect(blacklistErrorMessage(new TypeError('Failed to fetch'))).toContain('처리됐을 수')
})

it('등록 후 검색 조건을 비우고 페이지 크기는 유지한 채 활성 목록 첫 페이지를 조회한다', () => {
  expect(getBlacklistQueryAfterCreate({ hashValue: 'abc', severity: 'LOW', source: 'IMPORT', isActive: false, page: 3, size: 50 }))
    .toEqual({ isActive: true, page: 0, size: 50 })
})

it('직접 해시 전체로 검색한 항목을 해제하면 해당 해시 조건만 비우고 첫 페이지를 조회한다', () => {
  const hash = 'ab'.repeat(32)
  expect(getBlacklistQueryAfterStatusChange({ hashValue: hash.toUpperCase(), severity: 'HIGH', page: 0, size: 50 }, hash, false))
    .toEqual({ hashValue: undefined, severity: 'HIGH', page: 0, size: 50 })
})

it('일반 목록 해제나 다른 검색에서는 현재 조건을 유지해 페이지 보정에 맡긴다', () => {
  const hash = 'ab'.repeat(32)
  const query = { hashValue: 'ab', page: 1, size: 20 }
  expect(getBlacklistQueryAfterStatusChange(query, hash, false)).toEqual(query)
})

it('비활성 해시를 활성화하면 전체 해시 검색만 비우고 비활성 목록 조건은 유지한다', () => {
  const hash = 'ab'.repeat(32)
  expect(getBlacklistQueryAfterStatusChange({ hashValue: hash.toUpperCase(), isActive: false, source: 'IMPORT', page: 1, size: 50 }, hash, true))
    .toEqual({ hashValue: undefined, isActive: false, source: 'IMPORT', page: 0, size: 50 })
})

it('상태 변경 후에도 현재 목록에 해당하는 경우 전체 해시 검색을 유지한다', () => {
  const hash = 'ab'.repeat(32)
  const query = { hashValue: hash, isActive: false, page: 0, size: 20 }
  expect(getBlacklistQueryAfterStatusChange(query, hash, false)).toEqual(query)
})
