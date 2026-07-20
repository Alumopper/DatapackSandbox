"""Launch the Jupyter Kernel."""

from ipykernel.kernelapp import IPKernelApp

from .kernel import DatapackSandboxKernel


def main() -> None:
    IPKernelApp.launch_instance(kernel_class=DatapackSandboxKernel)


if __name__ == "__main__":
    main()
