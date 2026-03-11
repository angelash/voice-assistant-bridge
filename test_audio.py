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
            print(f"  ✅ {pkg} - {desc}")
            results[pkg] = True
        except ImportError:
            marker = "❌" if required else "⚠️ "
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
            print("  ⚠️  WSL2 环境可能没有音频设备")
            print("  解决方案: 1) 使用 PulseAudio 桥接到 Windows")
            print("           2) 使用 Windows 端客户端")
            return False
        
        return True
    except Exception as e:
        print(f"  ❌ 音频设备检查失败: {e}")
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
            print(f"  ✅ 合成成功，输出文件: {output_file} ({size} 字节)")
            return True
        else:
            print("  ❌ 合成失败，文件未生成")
            return False
            
    except ImportError:
        print("  ❌ edge-tts 未安装: pip install edge-tts")
        return False
    except Exception as e:
        print(f"  ❌ TTS 测试失败: {e}")
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
        print("  ✅ 模型加载成功")
        
        # 测试空音频（仅验证模型可用）
        import numpy as np
        silence = np.zeros(16000, dtype=np.float32)
        segments, info = model.transcribe(silence, language="zh")
        
        print(f"  ✅ 推理测试成功")
        return True
        
    except ImportError:
        print("  ❌ faster-whisper 未安装: pip install faster-whisper")
        return False
    except Exception as e:
        print(f"  ❌ STT 测试失败: {e}")
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
            print(f"  ✅ Ollama 服务可用 ({endpoint}:{port})")
            return True
        else:
            print(f"  ⚠️  Ollama 服务未运行 ({endpoint}:{port})")
            print("  启动方法: ollama serve")
            return False
    except Exception as e:
        print(f"  ❌ 连接测试失败: {e}")
        return False
    finally:
        sock.close()

def test_wakeword():
    """测试唤醒词检测（可选能力）"""
    print("\n" + "=" * 50)
    print("6. 测试唤醒词检测")
    print("=" * 50)

    try:
        import openwakeword
        from openwakeword import Model
        from pathlib import Path

        base = Path(openwakeword.__file__).resolve().parent
        resources_dir = base / "resources" / "models"
        if not resources_dir.exists():
            print("  ⚠️  openwakeword 已安装，但未找到内置模型资源目录")
            print(f"  包路径: {base}")
            print("  这通常意味着当前 wheel 未包含 models 资源，需重装或手动提供自定义模型")
            return False

        print("  加载默认模型 (hey_jarvis)...")
        model = Model()
        print("  ✅ 唤醒词模型加载成功")
        print("  内置唤醒词: hey_jarvis, alexa, hey_mycroft")
        return True

    except ImportError:
        print("  ⚠️  openwakeword 未安装（可选）: pip install openwakeword")
        return False
    except Exception as e:
        print(f"  ⚠️  唤醒词测试失败（可选能力）: {e}")
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
            status = "✅ 通过" if passed else "❌ 失败"
        else:
            status = "✅ 通过" if passed else "⚠️  待处理"
        print(f"  {name}: {status}")

    print("=" * 60)

    if ready_for_full_voice:
        print("\n🎉 主链路可用：语音助手已具备完整本地验证条件。")
        print("启动命令: python voice_assistant.py")
        return 0

    if ready_for_text_mode:
        print("\n✅ 核心能力可用：STT/TTS 已正常，项目可继续验证。")
        print("⚠️  当前还有环境项待处理（例如 WSL2 音频、Ollama、唤醒词资源）。")
        print("可先启动 Ollama 后运行: python voice_assistant.py")
        if not results["音频设备"]:
            print("\n💡 WSL2 音频解决方案:")
            print("   方案1: 安装 PulseAudio 桥接")
            print("   方案2: 使用 Windows 端客户端（见 windows_client.py）")
        if not results["LLM连接"]:
            print("\n💡 LLM 解决方案:")
            print("   ollama serve")
            print("   ollama pull qwen2.5:7b")
        if not results["唤醒词"] and dep_details.get("openwakeword"):
            print("\n💡 唤醒词解决方案:")
            print("   当前 openwakeword 包可能缺少内置模型资源；可重装、改用自定义模型，或暂时跳过唤醒词。")
        return 0

    print("\n❌ 核心能力仍未就绪，请先修复必需依赖或关键测试失败项。")
    return 1

if __name__ == "__main__":
    sys.exit(main())
