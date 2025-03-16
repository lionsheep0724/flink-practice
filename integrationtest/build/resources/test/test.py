import struct
import sys
import os

def analyze_wav_file(file_path):
    """
    WAV 파일의 헤더를 분석하여 오디오 형식 정보를 출력합니다.
    
    Args:
        file_path: WAV 파일 경로
    """
    try:
        with open(file_path, 'rb') as f:
            # RIFF 헤더 확인
            riff = f.read(4)
            if riff != b'RIFF':
                print(f"이 파일은 WAV 파일이 아닙니다. 헤더: {riff}")
                return
            
            # 파일 크기 (RIFF 청크 크기)
            chunk_size = struct.unpack('<I', f.read(4))[0]
            
            # WAVE 형식 확인
            wave = f.read(4)
            if wave != b'WAVE':
                print(f"이 파일은 WAV 파일이 아닙니다. 형식: {wave}")
                return
            
            # fmt 청크 찾기
            fmt_offset = None
            data_offset = None
            current_offset = 12  # RIFF(4) + size(4) + WAVE(4)
            
            # 모든 청크를 스캔하여 위치 기록
            f.seek(current_offset)
            while True:
                chunk_start = f.tell()
                chunk_id = f.read(4)
                if not chunk_id:
                    break
                
                chunk_size = struct.unpack('<I', f.read(4))[0]
                
                if chunk_id == b'fmt ':
                    fmt_offset = chunk_start
                elif chunk_id == b'data':
                    data_offset = chunk_start
                    data_size = chunk_size
                
                f.seek(chunk_size, 1)  # 현재 위치에서 chunk_size만큼 이동
            
            if fmt_offset is None:
                print("fmt 청크를 찾을 수 없습니다.")
                return
                
            if data_offset is None:
                print("data 청크를 찾을 수 없습니다.")
                return
            
            # fmt 청크로 이동
            f.seek(fmt_offset + 8)  # +8은 chunk_id(4) + chunk_size(4)를 건너뛰기 위함
            
            # 오디오 형식 정보
            audio_format = struct.unpack('<H', f.read(2))[0]  # 1: PCM
            num_channels = struct.unpack('<H', f.read(2))[0]  # 채널 수
            sample_rate = struct.unpack('<I', f.read(4))[0]   # 샘플레이트
            byte_rate = struct.unpack('<I', f.read(4))[0]     # 초당 바이트 수
            block_align = struct.unpack('<H', f.read(2))[0]   # 블록 정렬
            bits_per_sample = struct.unpack('<H', f.read(2))[0]  # 비트 깊이
            
            # 결과 출력
            print(f"파일: {file_path}")
            print(f"파일 크기: {os.path.getsize(file_path)} 바이트")
            print(f"오디오 형식: {'PCM' if audio_format == 1 else f'압축됨 (코드: {audio_format})'}")
            print(f"채널 수: {num_channels}")
            print(f"샘플레이트: {sample_rate} Hz")
            print(f"비트 깊이: {bits_per_sample} bits")
            print(f"초당 바이트 수: {byte_rate} bytes/sec")
            print(f"블록 정렬: {block_align} bytes")
            
            # 데이터 청크로 이동
            f.seek(data_offset + 8)  # +8은 chunk_id(4) + chunk_size(4)를 건너뛰기 위함
            
            print(f"오디오 데이터 크기: {data_size} 바이트")
            print(f"오디오 데이터 시작 위치: {f.tell()}")
            
            # 재생 시간 계산
            duration_seconds = data_size / byte_rate
            minutes = int(duration_seconds // 60)
            seconds = int(duration_seconds % 60)
            print(f"재생 시간: {minutes}분 {seconds}초")
            
            # 바이너리 데이터 확인
            print("\n바이너리 데이터 확인:")
            first_bytes = f.read(20)
            hex_values = ' '.join([f'{b:02X}' for b in first_bytes])
            print(f"첫 20바이트 (16진수): {hex_values}")
            
            # 데이터 청크로 다시 이동
            f.seek(data_offset + 8)
            
            # 첫 몇 개의 샘플 값 출력
            print("\n첫 20개 샘플 값:")
            bytes_per_sample = bits_per_sample // 8
            
            # 리틀 엔디안과 빅 엔디안 모두 시도
            for i in range(20):
                sample_bytes = f.read(bytes_per_sample)
                if not sample_bytes or len(sample_bytes) < bytes_per_sample:
                    break
                
                if bytes_per_sample == 2:
                    # 16비트 샘플 - 리틀 엔디안과 빅 엔디안 모두 시도
                    le_value = struct.unpack('<h', sample_bytes)[0]
                    be_value = struct.unpack('>h', sample_bytes)[0]
                    print(f"샘플 {i+1}: {le_value} (LE) / {be_value} (BE)")
                else:
                    # 다른 비트 깊이
                    if bytes_per_sample == 1:
                        sample_value = struct.unpack('B', sample_bytes)[0] - 128  # 8비트는 unsigned
                    elif bytes_per_sample == 3:
                        # 24비트 샘플 처리 (3바이트)
                        if sample_bytes[2] & 0x80:  # 부호 비트 확인
                            sample_bytes = sample_bytes + b'\xFF'  # 부호 확장
                        else:
                            sample_bytes = sample_bytes + b'\x00'
                        sample_value = struct.unpack('<i', sample_bytes)[0]
                    elif bytes_per_sample == 4:
                        if audio_format == 3:  # IEEE float
                            sample_value = struct.unpack('<f', sample_bytes)[0]
                        else:
                            sample_value = struct.unpack('<i', sample_bytes)[0]
                    print(f"샘플 {i+1}: {sample_value}")
            
            # 파일 전체에서 0이 아닌 샘플 찾기
            print("\n파일 전체 분석:")
            f.seek(data_offset + 8)  # 데이터 시작 위치로 다시 이동
            
            total_samples = data_size // bytes_per_sample
            non_zero_count = 0
            first_non_zero_pos = -1
            
            # 1000개 샘플마다 진행 상황 출력
            for i in range(total_samples):
                if i % 1000 == 0 and i > 0:
                    print(f"진행 중... {i}/{total_samples} 샘플 확인 ({i/total_samples*100:.1f}%)")
                
                sample_bytes = f.read(bytes_per_sample)
                if not sample_bytes or len(sample_bytes) < bytes_per_sample:
                    break
                
                if bytes_per_sample == 2:
                    sample_value = struct.unpack('<h', sample_bytes)[0]
                    if sample_value != 0:
                        non_zero_count += 1
                        if first_non_zero_pos == -1:
                            first_non_zero_pos = i
                            print(f"첫 번째 0이 아닌 샘플: 위치 {i}, 값 {sample_value}")
                        if non_zero_count <= 5:  # 처음 5개의 0이 아닌 값만 출력
                            print(f"0이 아닌 샘플 #{non_zero_count}: 위치 {i}, 값 {sample_value}")
            
            print(f"\n총 샘플 수: {total_samples}")
            print(f"0이 아닌 샘플 수: {non_zero_count} ({non_zero_count/total_samples*100:.2f}%)")
            
            if first_non_zero_pos != -1:
                print(f"첫 번째 0이 아닌 샘플까지의 시간: {first_non_zero_pos/sample_rate:.3f}초")
            
    except FileNotFoundError:
        print(f"파일을 찾을 수 없습니다: {file_path}")
    except Exception as e:
        print(f"오류 발생: {e}")
        import traceback
        traceback.print_exc()

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("사용법: python test.py <wav_file_path>")
    else:
        analyze_wav_file(sys.argv[1])
