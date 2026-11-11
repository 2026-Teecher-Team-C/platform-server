import { Link } from 'react-router-dom'
import type { EventSimilarity } from '../../lib/api/events'

// 최종 판정은 부모 화면에 그대로 둔다. 유사도는 별도 근거이며 악성의 독립 증거가 아니다.
export function SimilarityEvidence({ similarity, returnTo }: { similarity: EventSimilarity | null; returnTo?: string }) {
  const message = !similarity ? '기록된 유사도 평가 없음'
    : similarity.classification === 'BLOCK' ? '유사도 차단'
    : similarity.classification === 'WARN' ? '유사도 경고'
    : similarity.classification === 'UNAVAILABLE' ? 'TLSH 계산 불가'
    : similarity.distance === null ? '비교 가능한 기준 샘플 없음' : '경고 범위에 해당하는 유사 샘플 없음'
  // 문구는 접힌 설명에 두고, 아이콘의 접근성 이름과 툴팁으로 상태를 전달한다.
  const tone = similarity?.classification === 'BLOCK' ? 'block'
    : similarity?.classification === 'WARN' ? 'warn'
    : similarity?.classification === 'NONE' && similarity.distance !== null && similarity.distance > 70 ? 'success' : 'neutral'
  const symbol = tone === 'block' ? '×' : tone === 'warn' ? '!' : tone === 'success' ? '✓' : '?'
  const iconLabel = tone === 'success' ? 'TLSH 추가 조치 없음' : message
  return <section className="event-similarity" aria-label="TLSH 유사도">
    <div className="event-similarity-heading">
      <h2>TLSH 유사도 <span className={`event-similarity-icon event-similarity-icon--${tone}`} role="img" aria-label={iconLabel} title={iconLabel}>{symbol}</span></h2>
    </div>
    {similarity && <dl className="event-detail-grid">
      {/* 0도 실제 거리다. NULL인 경우 숫자나 가짜 기준 샘플을 만들지 않는다. */}
      {similarity.distance !== null && <div><dt>TLSH 거리</dt><dd>{similarity.distance}</dd></div>}
      <div><dt>평가 시각</dt><dd>{new Date(similarity.assessedAt).toLocaleString('ko-KR')}</dd></div>
      {similarity.matchedSampleSha256 !== null && <div className="event-detail-wide"><dt>최근접 기준 샘플 SHA-256</dt><dd className="event-long-value event-hash"><Link className="events-detail-link" to={'/samples/' + encodeURIComponent(similarity.matchedSampleSha256) + (returnTo ? '?returnTo=' + encodeURIComponent(returnTo) : '')}>{similarity.matchedSampleSha256}</Link></dd></div>}
    </dl>}
    {/* 기본은 접는다. native details는 키보드로도 열 수 있고 핵심 수치는 접힘과 무관하게 남는다. */}
    <details className="event-similarity-explanation" key={`${similarity?.classification}:${similarity?.assessedAt}`}>
      <summary>판정 기준과 설명</summary>
      <p><strong>{message}</strong></p>
      {!similarity && <p>평가하지 않았거나 이 이벤트에 연결된 기록이 없습니다. CheckHash만 조회한 이벤트도 여기에 해당할 수 있습니다.</p>}
      {/* 분류는 서버의 당시 평가를 따른다. 화면은 임계값으로 재판정하지 않고 처리 이유만 설명한다. */}
      {similarity?.distance !== null && similarity?.classification === 'BLOCK' && <p>{`거리 ${similarity.distance}은 차단 기준인 30 이하에 해당합니다.`} 활성 블랙리스트의 악성 기준 샘플과 유사하여 SIMILARITY 출처로 차단한 평가입니다.</p>}
      {similarity?.distance !== null && similarity?.classification === 'WARN' && <p>{`거리 ${similarity.distance}은 경고 구간인 31~70에 해당합니다.`} TLSH만으로는 차단하지 않고 유사 샘플 경고를 기록한 평가입니다.</p>}
      {similarity?.distance !== null && similarity?.classification === 'NONE' && <p>{`거리 ${similarity.distance}은 경고 기준인 70을 초과합니다.`} TLSH로 추가 차단하거나 경고하지 않고 기존 검사 판정을 유지한 평가입니다.</p>}
      {similarity?.classification === 'WARN' && <p>경고만으로 파일이 차단된 것은 아닙니다. 최종 판정을 함께 확인하세요.</p>}
      {similarity?.classification === 'UNAVAILABLE' && <p>계산 불가는 유사 샘플이 없다는 뜻이 아닙니다. 최종 판정은 기존 검사 결과를 따릅니다.</p>}
      <p>거리는 백분율이 아니며, 작을수록 기준 샘플과 유사합니다. 유사도만으로 악성을 입증하지 않습니다.</p>
      <div className="event-similarity-guide" aria-label="아이콘과 거리 기준">
        <h3>아이콘과 거리 기준</h3>
        <ul>
          <li><span className="event-similarity-icon event-similarity-icon--block" aria-hidden="true">×</span><span><strong>0~30: 유사도 차단</strong><br />활성 블랙리스트의 악성 기준 샘플과 유사해 추가 차단합니다.</span></li>
          <li><span className="event-similarity-icon event-similarity-icon--warn" aria-hidden="true">!</span><span><strong>31~70: 유사도 경고</strong><br />TLSH만으로는 차단하지 않습니다. 기존 검사를 통과한 파일은 통과하며 경고를 기록합니다.</span></li>
          <li><span className="event-similarity-icon event-similarity-icon--success" aria-hidden="true">✓</span><span><strong>70 초과: 추가 조치 없음</strong><br />TLSH로 추가 차단하거나 경고하지 않고 기존 판정을 유지합니다. 초록색 체크는 파일의 안전성을 입증하지 않습니다.</span></li>
          <li><span className="event-similarity-icon event-similarity-icon--neutral" aria-hidden="true">?</span><span><strong>거리 없음: 별도 확인 필요</strong><br />기준 샘플 없음, TLSH 계산 불가, 평가 기록 없음을 구분합니다. 정상 파일이라는 뜻은 아닙니다.</span></li>
        </ul>
      </div>
    </details>
  </section>
}
