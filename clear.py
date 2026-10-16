
import os
import subprocess
from datetime import datetime, timedelta

def run_command(cmd, check=True):
    """运行 shell 命令并处理错误"""
    result = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if check and result.returncode != 0:
        print(f"命令执行失败: {cmd}")
        print(f"标准输出: {result.stdout}")
        print(f"错误输出: {result.stderr}")
        raise subprocess.CalledProcessError(result.returncode, cmd)
    return result

def get_old_commits(days=2):
    """获取早于指定天数的提交"""
    threshold_date = (datetime.now() - timedelta(days=days)).strftime('%Y-%m-%d')
    cmd = f'git log --before="{threshold_date}" --format=%H'
    commits = run_command(cmd, check=False).stdout.strip().split('\n')
    return [c for c in commits if c]

def delete_old_commits():
    """删除2天前的提交并重写 master 分支"""
    old_commits = get_old_commits()
    if not old_commits:
        print("没有找到2天前的提交。")
        return

    # 确保在 master 分支并拉取最新文件
    run_command('git checkout master')
    run_command('git pull origin master')

    # 获取初始提交的哈希（只取第一个初始提交）
    initial_commit = run_command('git rev-list --max-parents=0 HEAD | head -n 1', check=True).stdout.strip()

    # 重置提交历史到初始提交，保留所有文件
    run_command(f'git reset --soft {initial_commit}')

    # 重新添加所有文件
    run_command('git rm -r --cached .')  # 移除所有文件以重新添加
    run_command('git add .')  # 重新添加所有文件

    # 创建新提交
    try:
        run_command('git commit --allow-empty -m "清理后的初始提交"')
    except subprocess.CalledProcessError as e:
        print(f"提交失败: {e}")
        return

    # 强制推送重写的 master 分支
    try:
        run_command('git push origin master --force')
    except subprocess.CalledProcessError as e:
        print(f"强制推送 master 分支失败: {e}")
        print("请确保已启用分支保护中的 '允许强制推送' 或有足够权限。")
        return

if __name__ == "__main__":
    try:
        delete_old_commits()
    except Exception as e:
        print(f"发生错误: {e}")
        exit(1)
