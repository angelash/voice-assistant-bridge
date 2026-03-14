#!/usr/bin/env python3
"""
音频系统测试脚本
检查录音、播放、STT、TTS 是否正常工作
"""

import sys
import asyncio
from pathlib import Path

def test_imports():
    """测试依赖是否安装，并区分核心依赖/可选依赖"""
    print("\n" + "=" * 50)
    print("1. 检查 Python 依赖")
    print("=" * 50)

    dependencies = {
        "numpy": ("数值计算", True),
        "scipy": ("信号处理", False),
        "sounddevice": ("音频 I/O", True),
        "edge_tts": ("TTS 语音合成", True),
        "faster_whisper": ("STT 语音识别", True),
        "aiohttp": ("HTTP 客户端", True),
        "openwakeword": ("唤醒词检测", False),
        "pyaudio": ("音频录制（备用）", False),
    }

    results = {}
    core_ok = True
    for pkg, (desc, required) in dependencies.items():
        try:
            __import__(pkg)
            print(f"  [PASS] {pkg} - {desc}")
            results[pkg] = True
        except ImportError:
            marker = "[FAIL]" if required else "[WARN] "
            suffix = "未安装"
            level = "核心" if required else "可选"
            print(f"  {marker} {pkg} - {desc} ({level}依赖，{suffix})")
            results[pkg] = False
            if required:
                core_ok = False

    return core_ok, results

def test_audio_devices():
    """测试音频设备"""
    print("\n" + "=" * 50)
    print("2. 检查音频设备")
    print("=" * 50)
    
    try:
        import sounddevice as sd
        
        devices = sd.query_devices()
        default_input = sd.query_devices(kind='input')
        default_output = sd.query_devices(kind='output')
        
        print(f"  默认输入设备: {default_input['name']}")
        print(f"  默认输出设备: {default_output['name']}")
        print(f"  总设备数: {len(devices)}")
        
        input_devices = [d for d in devices if d['max_input_channels'] > 0]
        output_devices = [d for d in devices if d['max_output_channels'] > 0]
        
        print(f"  输入设备: {len(input_devices)} 个")
        print(f"  输出设备: {len(output_devices)} 个")
        
        if not input_devices:
            print("  [WARN]  WSL2 环境可能没有音频设备")
            print("  解决方案: 1) 使用 PulseAudio 桥接到 Windows")
            print("           2) 使用 Windows 端客户端")
            return False
        
        return True
    except Exception as e:
        print(f"  [FAIL] 音频设备检查失败: {e}")
        return False

async def test_tts():
    """测试 TTS"""
    print("\n" + "=" * 50)
    print("3. 测试 TTS (语音合成)")
    print("=" * 50)
    
    try:
        import edge_tts
        
        text = "你好，我是语音助手，很高兴为你服务。"
        voice = "zh-CN-XiaoxiaoNeural"
        
        print(f"  合成文本: {text}")
        print(f"  使用声音: {voice}")
        
        communicate = edge_tts.Communicate(text, voice)
        
        output_file = Path(__file__).parent / "test_output.mp3"
        await communicate.save(str(output_file))
        
        if output_file.exists():
            size = output_file.stat().st_size
            print(f"  [PASS] 合成成功，输出文件: {output_file} ({size} 字节)")
            return True
        else:
            print("  [FAIL] 合成失败，文件未生成")
            return False
            
    except ImportError:
        print("  [FAIL] edge-tts 未安装: pip install edge-tts")
        return False
    except Exception as e:
        print(f"  [FAIL] TTS 测试失败: {e}")
        return False

def test_stt():
    """测试 STT"""
    print("\n" + "=" * 50)
    print("4. 测试 STT (语音识别)")
    print("=" * 50)
    
    try:
        from faster_whisper import WhisperModel
        
        print("  加载模型 tiny (测试用)...")
        model = WhisperModel("tiny", device="cpu", compute_type="int8")
        print("  [PASS] 模型加载成功")
        
        # 测试空音频（仅验证模型可用）
        import numpy as np
        silence = np.zeros(16000, dtype=np.float32)
        segments, info = model.transcribe(silence, language="zh")
        
        print(f"  [PASS] 推理测试成功")
        return True
        
    except ImportError:
        print("  [FAIL] faster-whisper 未安装: pip install faster-whisper")
        return False
    except Exception as e:
        print(f"  [FAIL] STT 测试失败: {e}")
        return False

def test_llm_connection():
    """测试 LLM 连接"""
    print("\n" + "=" * 50)
    print("5. 测试 LLM 连接")
    print("=" * 50)
    
    import socket
    
    endpoint = "localhost"
    port = 11434
    
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(2)
    
    try:
        result = sock.connect_ex((endpoint, port))
        if result == 0:
            print(f"  [PASS] Ollama 服务可用 ({endpoint}:{port})")
            return True
        else:
            print(f"  [WARN]  Ollama 服务未运行 ({endpoint}:{port})")
            print("  启动方法: ollama serve")
            return False
    except Exception as e:
        print(f"  [FAIL] 连接测试失败: {e}")
        return False
    finally:
        sock.close()

def ensure_openwakeword_model(model_name: str = "hey_jarvis") -> bool:
    """Ensure a built-in model is present locally; download it when missing."""
    import openwakeword

    model_paths = openwakeword.get_pretrained_model_paths()
    has_local_model = any(
        model_name in Path(path).name and Path(path).exists()
        for path in model_paths
    )
    if has_local_model:
        return True

    print(f"  [INFO] 未检测到内置模型，尝试下载: {model_name}")
    try:
        from openwakeword.utils import download_models
        download_models(model_names=[model_name])
    except Exception as e:
        print(f"  [WARN]  模型自动下载失败: {e}")
        return False

    model_paths = openwakeword.get_pretrained_model_paths()
    return any(
        model_name in Path(path).name and Path(path).exists()
        for path in model_paths
    )

def test_wakeword():
    """测试唤醒词检测（可选能力）"""
    print("\n" + "=" * 50)
    print("6. 测试唤醒词检测")
    print("=" * 50)

    try:
        from openwakeword import Model
        model_name = "hey_jarvis"
        if not ensure_openwakeword_model(model_name):
            print(f"  [WARN]  无法准备唤醒词模型: {model_name}")
            return False

        print(f"  加载内置模型 ({model_name})...")
        _model = Model(wakeword_models=[model_name])
        print("  [PASS] 唤醒词模型加载成功")
        print("  内置唤醒词: hey_jarvis, alexa, hey_mycroft")
        return True

    except ImportError:
        print("  [WARN]  openwakeword 未安装（可选）: pip install openwakeword")
        return False
    except Exception as e:
        print(f"  [WARN]  唤醒词测试失败（可选能力）: {e}")
        return False

def main():
    print("\n" + "=" * 60)
    print("  语音助手系统测试")
    print("  Voice Assistant System Test")
    print("=" * 60)

    deps_ok, dep_details = test_imports()
    results = {
        "依赖安装": deps_ok,
        "音频设备": test_audio_devices(),
        "TTS": asyncio.run(test_tts()),
        "STT": test_stt(),
        "LLM连接": test_llm_connection(),
        "唤醒词": test_wakeword(),
    }

    print("\n" + "=" * 60)
    print("  测试结果汇总")
    print("=" * 60)

    hard_requirements = ["依赖安装", "TTS", "STT"]
    ready_for_text_mode = all(results[name] for name in hard_requirements)
    ready_for_full_voice = ready_for_text_mode and results["音频设备"] and results["LLM连接"]

    for name, passed in results.items():
        if name in hard_requirements:
            status = "[PASS] 通过" if passed else "[FAIL] 失败"
        else:
            status = "[PASS] 通过" if passed else "[WARN]  待处理"
        print(f"  {name}: {status}")

    print("=" * 60)

    if ready_for_full_voice:
        print("\n[PASS] 主链路可用：语音助手已具备完整本地验证条件。")
        print("启动命令: python voice_assistant.py")
        return 0

    if ready_for_text_mode:
        print("\n[PASS] 核心能力可用：STT/TTS 已正常，项目可继续验证。")
        print("[WARN]  当前还有环境项待处理（例如 WSL2 音频、Ollama、唤醒词资源）。")
        print("可先启动 Ollama 后运行: python voice_assistant.py")
        if not results["音频设备"]:
            print("\n[INFO] WSL2 音频解决方案:")
            print("   方案1: 安装 PulseAudio 桥接")
            print("   方案2: 使用 Windows 端客户端（见 windows_client.py）")
        if not results["LLM连接"]:
            print("\n[INFO] LLM 解决方案:")
            print("   ollama serve")
            print("   ollama pull qwen2.5:7b")
        if not results["唤醒词"] and dep_details.get("openwakeword"):
            print("\n[INFO] 唤醒词解决方案:")
            print("   当前 openwakeword 包可能缺少内置模型资源；可重装、改用自定义模型，或暂时跳过唤醒词。")
        return 0

    print("\n[FAIL] 核心能力仍未就绪，请先修复必需依赖或关键测试失败项。")
    return 1

if __name__ == "__main__":
    sys.exit(main())

