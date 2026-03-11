#!/bin/bash
# 语音助手安装脚本

set -e

echo "========================================"
echo "  语音助手安装脚本"
echo "========================================"

# 检查 Python
if ! command -v python3 &> /dev/null; then
    echo "❌ Python3 未安装"
    exit 1
fi

echo "✅ Python: $(python3 --version)"

# 检查 pip
if ! command -v pip3 &> /dev/null; then
    echo "❌ pip 未安装"
    exit 1
fi

echo "✅ pip: $(pip3 --version)"

# 创建虚拟环境（可选）
read -p "是否创建虚拟环境？(y/n) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    python3 -m venv venv
    source venv/bin/activate
    echo "✅ 虚拟环境已激活"
fi

# 安装依赖
echo ""
echo "安装 Python 依赖..."
pip3 install -r requirements.txt

echo ""
echo "========================================"
echo "  安装完成"
echo "========================================"
echo ""
echo "下一步："
echo "1. 运行测试: python test_audio.py"
echo "2. 启动助手: python voice_assistant.py"
echo ""
echo "WSL2 用户："
echo "  Windows 端运行: python windows_client.py --server http://localhost:8765"
echo "  WSL2 端运行: python server.py"
